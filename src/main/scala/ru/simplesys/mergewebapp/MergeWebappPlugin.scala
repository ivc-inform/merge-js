package com.simplesys
package mergewebapp

import java.io.{File, InputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipInputStream

import com.simplesys.common.Strings._
import sbt.ErrorHandling._
import sbt.Keys._
import sbt.Using.{fileInputStream, fileOutputStream, zipInputStream}
import sbt._

import scala.collection.mutable.HashSet
import scala.xml.{Elem, XML, Node ⇒ XmlNode}

object MergeWebappPlugin extends AutoPlugin {

    override def requires = sbt.plugins.JvmPlugin
    override lazy val projectSettings: Seq[Setting[_]] = mergeWebappSettings

    val MergeWebappConfig = config("merge-webapp")

    val merge = taskKey[Seq[File]]("Merge webapp folder")
    val mergeMapping = settingKey[Seq[((String, String), Seq[(Seq[String], Option[Seq[String]])])]]("Per-library folder mappings")
    val dirIndexFileNamePath = settingKey[File]("Dir where root index file will be placed")
    val currentProjectGenerationDirPath = settingKey[File]("Current project generation JS dir path")
    val currentProjectDevelopedDirPath = settingKey[File]("Current project developed JS dir path")
    val currentProjectCoffeeDevelopedDirPath = settingKey[File]("Current project developed CoffeeScript dir path")
    val indexFileName = settingKey[String]("JavaScript index file name")

    val intSettingFileName = "lastSaveMappingSettings.ignore"


    lazy val mergeWebappSettings: Seq[Setting[_]] = inConfig(MergeWebappConfig)(Seq[Setting[_]](
        indexFileName := "IncludeModules",
        //internalSettingFileName := "lastSaveMappingSettings.ignore",

        dirIndexFileNamePath := (sourceDirectory in Compile).value / "webapp" / "javascript",
        currentProjectCoffeeDevelopedDirPath := (sourceDirectory in Compile).value / "webapp" / "coffeescript",
        merge := {
            val out = streams.value
            val iFileName = indexFileName.value
            val iDirIndexFileName = dirIndexFileNamePath.value
            val currProjGenDir = currentProjectGenerationDirPath.value
            val currProjDevDir = currentProjectDevelopedDirPath.value
            val currProCsDevDir = currentProjectCoffeeDevelopedDirPath.value
            val srcDir = (sourceDirectory in Compile).value
            val managedcp = (dependencyClasspath in Compile).value
            val libraryDeps = (libraryDependencies in Compile).value
            val tempDir = taskTemporaryDirectory.value
            val scalaVer = scalaVersion.value
            val scalaVerBinary = scalaBinaryVersion.value
            val mapping = mergeMapping.value


            implicit val logger = out.log

            val currentMappingSettings = mapping.map { case ((groupNameMapp, artifactNameMapp), folderMapping) =>
                val libDep: ModuleID = libraryDeps.find(m => m.organization == groupNameMapp && m.name == artifactNameMapp) match {
                    case None => throw new RuntimeException(s"m.organization = $groupNameMapp, m.name = $artifactNameMapp")
                    case Some(x) => x
                }

                val fullLibDep = CrossVersion.apply(scalaVer, scalaVerBinary)(libDep)
                MappingDirectories.applyOrig(fullLibDep.organization, fullLibDep.name, fullLibDep.revision, folderMapping)
            }

            val internalStoreFile = iDirIndexFileName / intSettingFileName
            val previousMappingSettings = {
                if (internalStoreFile.exists()) {
                    out.log.debug(s"merger plugin: loading previous mappings from ${internalStoreFile.getAbsolutePath}")
                    val loaded = XML.loadFile(iDirIndexFileName / intSettingFileName)
                    val mapp = (loaded \\ "mapping").map(n => MappingDirectories(n))
                    mapp
                } else {
                    out.log.debug(s"merger plugin: previous mappings save file not found")
                    Seq.empty
                }

            }

            val toDelete = previousMappingSettings.filter { p =>
                !currentMappingSettings.contains(p) || p.isSnapshot
            }

            val toAdd = currentMappingSettings.filter { c =>
                !previousMappingSettings.contains(c) || c.isSnapshot
            }


            currentMappingSettings.withFilter { mapp => !toAdd.contains(mapp) }.foreach { m =>
                out.log.info(s"merger plugin: skipping ${m.organization}:${m.artifact}:${m.revision} since version and mappings are the same")
            }


            toDelete.foreach { lib =>
                lib.deleteUnpacked(srcDir)
            }

            toAdd.foreach { lib =>
                lib.unpackMappings(managedcp, tempDir, srcDir)
            }

            val rootIndexFile = iDirIndexFileName / iFileName
            val index = scala.collection.mutable.ListBuffer.empty[String]

            out.log.info(s"merger plugin: rebuilding IncludeModules at ${rootIndexFile.getAbsolutePath}")

            currentMappingSettings.foreach { m =>
                m.mapping.foreach { mp =>
                    val prependPath = mp.destination.mkString("""/""") + """/"""
                    val libIndexFile = mp.destinationDir(srcDir) / iFileName
                    if (libIndexFile.exists()) {
                        logger.debug(s"merger plugin: reading IncludeModules as ${libIndexFile.getAbsolutePath}")
                        index ++= IO.readLines(libIndexFile, StandardCharsets.UTF_8).filter(_.trim != "").withOutComment.filter(_.indexOf(".coffee") == -1).map(x => prependPath + x)
                    } else
                        logger.error(s"merger plugin: IncludeModules is not exists for ${m.organization}:${m.artifact}:${m.revision} at ${libIndexFile.getAbsolutePath}")

                }
            }

            val currGenPath = currProjGenDir.relativeTo(srcDir).get.getPath + """/"""
            val currGenIndexFile = currProjGenDir / iFileName
            if (currGenIndexFile.exists()) {
                logger.debug(s"merger plugin: reading IncludeModules as ${currGenIndexFile.getAbsolutePath}")
                index ++= IO.readLines(currGenIndexFile, StandardCharsets.UTF_8).filter(_.trim != "").withOutComment.map(x => currGenPath + x)
            } else
                logger.error(s"merger plugin: IncludeModules is not exists for generated JS at ${currGenIndexFile.getAbsolutePath}")

            val currDevPath = currProjDevDir.relativeTo(srcDir).get.getPath + """/"""
            val currDevIndexFile = currProjDevDir / iFileName
            if (currDevIndexFile.exists()) {
                logger.debug(s"merger plugin: reading IncludeModules as ${currDevIndexFile.getAbsolutePath}")
                index ++= IO.readLines(currDevIndexFile, StandardCharsets.UTF_8).filter(_.trim != "").withOutComment.map(x => currDevPath + x)
            } else
                logger.error(s"merger plugin: IncludeModules is not exists for developed JS at ${currDevIndexFile.getAbsolutePath}")

            val currDevCsPath = currProjGenDir.relativeTo(srcDir).get.getPath + """/coffeescript/"""
            val currDevCsIndexFile = currProCsDevDir / iFileName
            if (currDevCsIndexFile.exists()) {
                logger.debug(s"merger plugin: reading IncludeModules as ${currDevCsIndexFile.getAbsolutePath}")
                index ++= IO.readLines(currDevCsIndexFile, StandardCharsets.UTF_8).filter(_.trim != "").withOutComment.map(x => currDevCsPath + x.replace(".coffee", ".js"))
            } else
                logger.error(s"merger plugin: IncludeModules is not exists for developed CoffeeScript at ${currDevCsIndexFile.getAbsolutePath}")

            IO.writeLines(rootIndexFile, index, StandardCharsets.UTF_8, false)

            IO.delete(internalStoreFile)
            //val internalStore = iDirIndexFileName / intSettingFileName
            out.log.debug(s"merger plugin: storing current mappings at ${internalStoreFile.getAbsolutePath}")
            val settings: Elem = <mappings>
                {currentMappingSettings.map(_.toXML)}
            </mappings>

            com.simplesys.xml.XML.save(internalStoreFile.getAbsolutePath, settings, scala.io.Codec.UTF8.toString())
            out.log.info(s"merger plugin: merging completed !!!!!!!!!!!!!!")
            Seq()
        }

    )) ++ Seq[Setting[_]](
        resourceGenerators in Compile <+= merge in MergeWebappConfig
    )
}

object UnpackUtils {
    private def sha1 = MessageDigest.getInstance("SHA-1")

    private def sha1content(f: File): String =
        Vector(sha1.digest(IO.readBytes(f)): _*) map {
            "%02x".format(_)
        } mkString

    private def sha1name(f: File): String =
        Vector(sha1.digest(f.getCanonicalPath.getBytes): _*) map {
            "%02x".format(_)
        } mkString

    def unzipFile(from: File, toDirectory: File, filter: NameFilter = AllPassFilter): Set[File] =
        fileInputStream(from)(in => unzipStream(in, toDirectory, filter))

    def unzipStream(from: InputStream, toDirectory: File, filter: NameFilter): Set[File] = {
        IO.createDirectory(toDirectory)
        zipInputStream(from) {
            zipInput => extract(zipInput, toDirectory, filter)
        }
    }

    private def extract(from: ZipInputStream, toDirectory: File, filter: NameFilter) = {
        val set = new HashSet[File]
        def next() {
            val entry = from.getNextEntry
            if (entry == null)
                ()
            else {
                val name = entry.getName
                if (filter.accept(name)) {
                    val target = new File(toDirectory, name)
                    //log.trace("Extracting zip entry '" + name + "' to '" + target + "'")

                    if (entry.isDirectory)
                        IO.createDirectory(target)
                    else {
                        set += target
                        translate("Error extracting zip entry '" + name + "' to '" + target + "': ") {
                            fileOutputStream(false)(target) {
                                out => IO.transfer(from, out)
                            }
                        }
                    }
                }
                else {
                    //log.trace("Ignoring zip entry '" + name + "'")
                }
                from.closeEntry()
                next()
            }
        }
        next()
        Set() ++ set
    }
}

case class MappingPair(from: Seq[String], to: Option[Seq[String]]) {
    def destination: Seq[String] = to getOrElse from

    def destinationDir(relativeDir: File) = destination.foldLeft(relativeDir)((path, x) => path / x)

    def sourceDir(relativeDir: File) = from.foldLeft(relativeDir)((path, x) => path / x)

    //def toXML: Elem = <mappingPair><from>{from.map(s => <level>{s}</level>)}</from>{to.map { t => <to>{t.map(s => <level>{s}</level>)}</to>} orNull}</mappingPair> //Так должно быть !!
    def toXML: Elem = <mappingPair>
        <from>
            {from.map(s => <level>
            {s}
        </level>)}
        </from>{to.map { t => <to>
            {t.map(s => <level>
                {s}
            </level>)}
        </to>
        } orNull}
    </mappingPair>
}

object MappingPair {
    def apply(x: XmlNode): MappingPair = {
        val fromMapp = (x \ "from").flatMap { e =>
            (e \ "level").map(_.text)
        }

        val toMapp = {
            val r = (x \ "to").flatMap { e =>
                (e \ "level").map(_.text)
            }
            if (r.isEmpty) None else Some(r)
        }
        MappingPair(fromMapp, toMapp)
    }
}

case class MappingDirectories(organization: String, artifact: String, revision: String, mapping: Seq[MappingPair]) {
    val isSnapshot = revision.toLowerCase.endsWith("-snapshot")

    def toXML: Elem = <mapping organization={organization} artifact={artifact} revision={revision}>
        <content>
            {mapping.map(_.toXML)}
        </content>
    </mapping>

    def deleteUnpacked(srcDir: File)(implicit logger: Logger): Unit = {
        logger.info(s"merger plugin: deleting directories for $organization:$artifact:$revision")
        mapping.foreach { mp =>
            val webappDest = mp.destinationDir(srcDir)
            logger.debug(s"merger plugin: deleting directory ${webappDest.getAbsolutePath}")
            webappDest.delete()
        }
        logger.info(s"merger plugin: deleting directories for $organization:$artifact:$revision is done")
    }

    def getLibFile(managedClasspath: Seq[Attributed[File]]): File = {
        managedClasspath.filter(_.get(Keys.moduleID.key).exists(x => (x.name == artifact) && (x.organization == organization) && (x.revision == revision))).headOption match {
            case None ⇒
                managedClasspath.filter(_.get(Keys.moduleID.key).exists(x => (x.name == artifact))).headOption match {
                    case None ⇒
                        throw new RuntimeException(s"Not found: artifact = $artifact, organization = $organization, revision = $revision")
                    case Some(lib) ⇒
                        throw new RuntimeException(s"Not found: artifact = $artifact, organization = $organization, revision = $revision but finded: ${lib.data.getAbsolutePath}")
                }
            case Some(lib) ⇒
                lib.data
        }
    }

    def unpackMappings(managedClasspath: Seq[Attributed[File]], tempDir: File, srcDir: File)(implicit logger: Logger): Unit = {
        val sourceFile: File = getLibFile(managedClasspath)
        logger.info(newLine)
        logger.info(s"merger plugin: unpacking directories for $organization:$artifact:$revision")
        mapping.foreach { mp =>
            val extractPattern = mp.from.reduceLeft((path, x) => path + "/" + x) + "/*"
            logger.debug(s"merger plugin: unpacking with filter $extractPattern to ${tempDir.getAbsolutePath}")
            UnpackUtils.unzipFile(sourceFile, tempDir, FileFilter.globFilter(extractPattern))
            val sourceDir = mp.sourceDir(tempDir)
            val destDir = mp.destinationDir(srcDir)
            logger.debug(s"merger plugin: copying to ${destDir.getAbsolutePath} from ${sourceDir.getAbsolutePath}")
            IO.delete(destDir)
            IO.copyDirectory(sourceDir, destDir, true)
            logger.info(s"Coping: from: ${sourceDir.getAbsolutePath} to: ${destDir.getAbsolutePath}")
            IO.delete(sourceDir)
        }
        logger.info(s"merger plugin: unpacking directories for $organization:$artifact:$revision is done".newLine)
    }

}

object MappingDirectories {
    def apply(x: XmlNode): MappingDirectories = {
        val organization = (x \ "@organization").text
        val artifact = (x \ "@artifact").text
        val revision = (x \ "@revision").text


        val r = (x \\ "mappingPair").map { m => MappingPair(m) }

        MappingDirectories(organization, artifact, revision, r)
    }

    def applyOrig(organization: String, artifact: String, revision: String, mapping: Seq[(Seq[String], Option[Seq[String]])]): MappingDirectories = {
        val mappingPairs = mapping.map { case (from, toOpt) => MappingPair(from, toOpt) }
        new MappingDirectories(organization, artifact, revision, mappingPairs)
    }
}

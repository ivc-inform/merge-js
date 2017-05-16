sbtPlugin := true

name := "merge-js"

//enablePlugins(GitVersioning)

organization := "ru.simplesys"

version := "1.0.9"

//version := "1.0.8-SNAPSHOT"

scalaVersion := "2.10.6"

scalacOptions := Seq(
    "-feature",
    "-language:postfixOps",
    //        "-language:higherKinds",
    //        "-language:implicitConversions",
    "-deprecation",
    "-unchecked")

description := "sbt plugin to merge webapps from dependencies"

libraryDependencies ++= {
	val ssysCoreVersion = "1.2.100"
	//val ssysCoreVersion = "1.2-SNAPSHOT"
	Seq(
    		"com.simplesys.core" %% "common" % ssysCoreVersion,
    		"com.simplesys.core" %% "xml-extender" % ssysCoreVersion
	)
}	

publishArtifact in(Compile, packageBin) := true

publishArtifact in(Test, packageBin) := false

publishArtifact in(Compile, packageDoc) := false

publishArtifact in(Compile, packageSrc) := true

publishMavenStyle := true

git.baseVersion := "1.0.11"


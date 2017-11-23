sbtPlugin := true

name := "merge-js"

organization := "ru.simplesys"

version := "1.0.14-SNAPSHOT"

scalaVersion := "2.12.4"

scalacOptions := Seq(
    "-feature",
    "-language:postfixOps",
    //        "-language:higherKinds",
    //        "-language:implicitConversions",
    "-deprecation",
    "-unchecked")

description := "sbt plugin to merge webapps from dependencies"

libraryDependencies ++= {
	//val ssysCoreVersion = "1.4.0.2"
	val ssysCoreVersion = "1.5-SNAPSHOT"
	Seq(
    		"com.simplesys.core" %% "common" % ssysCoreVersion,
    		"com.simplesys.core" %% "xml-extender" % ssysCoreVersion,
    		"com.simplesys.core" %% "scala-io-extender" % ssysCoreVersion
	)
}	

publishArtifact in(Compile, packageBin) := true

publishArtifact in(Test, packageBin) := false

publishArtifact in(Compile, packageDoc) := false

publishArtifact in(Compile, packageSrc) := true

publishMavenStyle := true


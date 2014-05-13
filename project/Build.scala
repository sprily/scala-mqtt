import sbt._
import Keys._

object ScalaMqtt extends Build {

  lazy val globalSettings = Defaults.defaultSettings ++ Seq(
    scalaVersion in ThisBuild := "2.10.3",
    organization in ThisBuild := "uk.co.sprily",
    version in ThisBuild := "0.1-SNAPSHOT",
    scalacOptions in ThisBuild ++= Seq("-deprecation", "-unchecked", "-feature"),

    resolvers ++= Seq(
      "TypeSafe Releases" at "http://repo.typesafe.com/typesafe/releases",
      "spring"            at "http://repo.springsource.org/plugins-release"
    ),

    // Standardise some common dependencies.
    libraryDependencies ++= Seq(
      "org.eclipse.paho"       % "mqtt-client"                 % "0.4.0",
      "com.typesafe"          %% "scalalogging-slf4j"          % "1.0.1",
      "org.scalacheck"        %% "scalacheck"                  % "1.11.1"           % "test",
      "org.scalatest"         %% "scalatest"                   % "2.0"              % "test",
      "org.scalamock"         %% "scalamock-scalatest-support" % "3.1.RC1"    % "test"
    ),

    testOptions in Test := Seq(Tests.Filter(unitTestFilter)),
    testOptions in IntegrationTest := Seq(Tests.Filter(integrationTestFilter)),
    parallelExecution in IntegrationTest := false
  ) ++ inConfig(IntegrationTest)(Defaults.testTasks)

  lazy val root      = Project(id        = "scala-mqtt",
                               base      = file("."),
                               aggregate = Seq(core, rx))
                         .configs(IntegrationTest)

  lazy val core      = Project(id        = "scala-mqtt-core",
                               base      = file("core"),
                               settings  = globalSettings)
                         .configs(IntegrationTest)

  lazy val rx        = Project(id        = "scala-mqtt-rx",
                               base      = file("rx"),
                               settings  = globalSettings ++ Seq(
                                 libraryDependencies ++= Seq(
                                   "com.netflix.rxjava"          % "rxjava-scala"    % "0.16.1"
                                 )
                               )).configs(IntegrationTest)
                                 .dependsOn(core)

  lazy val IntegrationTest = config("it") extend(Test)
  def unitTestFilter(name: String): Boolean = ! integrationTestFilter(name)
  def integrationTestFilter(name: String): Boolean = name endsWith "IntegrationTests"
}

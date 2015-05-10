import sbt._
import Keys._

object ScalaMqtt extends Build {

  lazy val globalSettings = Defaults.defaultSettings ++ Seq(
    scalaVersion in ThisBuild := "2.11.6",
    //crossScalaVersions in ThisBuild := Seq("2.10.3", "2.11.2"),   // scala-logging is 2.11 only
    organization in ThisBuild := "uk.co.sprily",
    version in ThisBuild := "0.1.1",
    scalacOptions in ThisBuild ++= Seq("-deprecation", "-unchecked", "-feature"),

    resolvers ++= Seq(
      "TypeSafe Releases" at "http://repo.typesafe.com/typesafe/releases",
      "spring"            at "http://repo.springsource.org/plugins-release",
      "Paho Releases"     at "https://repo.eclipse.org/content/repositories/paho-releases"
    ),

    // Standardise some common dependencies.
    libraryDependencies ++= Seq(
      "org.eclipse.paho"            % "org.eclipse.paho.client.mqttv3" % "1.0.1",
      "com.typesafe.scala-logging" %% "scala-logging"               % "3.1.0",
      "ch.qos.logback"              % "logback-core"                % "1.1.2",
      "ch.qos.logback"              % "logback-classic"             % "1.1.2",
      "org.scalaz"                 %% "scalaz-core"                 % "7.1.0",
      "org.scalaz"                 %% "scalaz-scalacheck-binding"   % "7.1.0"            % "test",
      "org.scalacheck"             %% "scalacheck"                  % "1.10.1"           % "test",
      "org.scalatest"              %% "scalatest"                   % "2.2.1"            % "test"
    ),

    testOptions in Test := Seq(Tests.Filter(unitTestFilter)),
    testOptions in IntegrationTest := Seq(Tests.Filter(integrationTestFilter)),
    parallelExecution in IntegrationTest := false,

    publishTo := {
      val nexus = "https://repo.sprily.co.uk/nexus/"
      if (version.value.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "content/repositories/releases")
    }

  ) ++ inConfig(IntegrationTest)(Defaults.testTasks)

  lazy val root      = Project(id        = "scala-mqtt",
                               base      = file("."),
                               settings  = globalSettings,
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
                                   "com.netflix.rxjava"          % "rxjava-scala"    % "0.20.4"
                                 )
                               )).configs(IntegrationTest)
                                 .dependsOn(core)

  lazy val IntegrationTest = config("it") extend(Test)
  def unitTestFilter(name: String): Boolean = ! integrationTestFilter(name)
  def integrationTestFilter(name: String): Boolean = name endsWith "IntegrationTests"
}

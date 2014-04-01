import sbt._
import sbt.Keys._

object BuildSettings {
  val buildVersion = "0.2-SNAPSHOT"

  val buildSettings = Defaults.defaultSettings ++ Seq(
    organization := "com.github.fdimuccio",
    version := buildVersion,
    scalaVersion := "2.10.3",
    crossScalaVersions := Seq("2.10.3"),
    crossVersion := CrossVersion.binary,
    scalacOptions in Test ++= Seq("-Yrangepos")
  ) ++ Publish.settings
}

object Publish {
  object TargetRepository {
    def local: Project.Initialize[Option[sbt.Resolver]] = version { (version: String) =>
      val localPublishRepo = Path.userHome.absolutePath + "/.m2/repository"
      if(version.trim.endsWith("SNAPSHOT"))
        Some(Resolver.file("snapshots", new File(localPublishRepo + "/snapshots")))
      else Some(Resolver.file("releases", new File(localPublishRepo + "/releases")))
    }
    def sonatype: Project.Initialize[Option[sbt.Resolver]] = version { (version: String) =>
      val nexus = "https://oss.sonatype.org/"
      if (version.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    }
  }
  lazy val settings = Seq(
    publishMavenStyle := true,
    publishTo <<= TargetRepository.local,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    licenses := Seq("Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    homepage := Some(url("https://github.com/fdimuccio")),
    pomExtra :=
      <scm>
        <url>git@github.com:fdimuccio/play2-sockjs</url>
        <connection>scm:git:git@github.com:/play2-sockjs.git</connection>
      </scm>
      <developers>
        <developer>
          <id>fdimuccio</id>
          <name>Francesco Di Muccio</name>
          <url>https://github.com/fdimuccio</url>
        </developer>
      </developers>
  )
}

object Play2SockJSBuild extends Build {
  import BuildSettings._

  lazy val play2SockJS = Project(
    "play2-sockjs",
    file("."),
    settings = buildSettings ++ Seq(
      resolvers := Seq(
        Resolver.sonatypeRepo("snapshots"),
        Resolver.sonatypeRepo("releases"),
        Resolver.typesafeRepo("snapshots"),
        Resolver.typesafeRepo("releases")
      ),
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-actor" % "2.2.4",
        "com.typesafe.play" %% "play" % "2.2.2" % "provided",
        "com.typesafe.play" %% "play-test" % "2.2.2" % "test",
        "org.specs2" %% "specs2" % "2.3.10" % "test",
        "junit" % "junit" % "4.8" % "test" cross CrossVersion.Disabled
      )
    )
  )
}
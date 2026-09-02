organization := "com.github.kmizu"

name := "macro_peg"

scalaVersion := "3.3.8"

publishMavenStyle := true

val scaladocBranch = settingKey[String]("branch name for scaladoc -doc-source-url")

scaladocBranch := "main"

Compile / doc / scalacOptions ++= Seq(
  "-sourcepath", baseDirectory.value.getAbsolutePath,
  "-doc-source-url", s"https://github.com/kmizu/macro_peg/tree/${scaladocBranch.value}€{FILE_PATH}.scala",
)

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-language:implicitConversions")

libraryDependencies ++= Seq(
  ("com.github.kmizu" %% "scomb" % "0.9.0").cross(CrossVersion.for3Use2_13),
  "org.scalatest" %% "scalatest" % "3.2.20" % Test,
  "org.scalatestplus" %% "scalacheck-1-19" % "3.2.20.0" % Test,
  "org.scalacheck" %% "scalacheck" % "1.19.0" % Test
)

Test / fork := true
Test / javaOptions += "-Xss128m"
Test / parallelExecution := false

console / initialCommands += {
  Iterator(
    "com.github.kmizu.macro_peg.combinator.MacroParsers._"
  ).map("import " + _).mkString("\n")
}

// ---- POM metadata (sbt 2 builds are Scala 3, so no XML literals) ----

homepage := Some(url("https://github.com/kmizu/macro_peg"))
licenses := Seq("MIT" -> url("http://www.opensource.org/licenses/MIT"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/kmizu/macro_peg"),
    "scm:git:git@github.com:kmizu/macro_peg.git"
  )
)
developers := List(
  Developer("kmizu", "Kota Mizushima", "", url("https://github.com/kmizu"))
)
pomIncludeRepository := (_ => false)

// ---- Publishing via Sonatype Central (built into sbt 2; no sbt-sonatype needed) ----
//
// Releases:  `publishSigned` stages into target/sona-staging, then `sonaRelease` uploads
//            the bundle to the Central Portal and releases it.
// Snapshots: `publishSigned` uploads directly to the Central snapshots repository.

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (version.value.endsWith("-SNAPSHOT")) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

credentials ++= {
  val host = "central.sonatype.com"
  val realm = "Sonatype Central"
  val envCredentials = for {
    u <- sys.env.get("SONATYPE_USERNAME")
    p <- sys.env.get("SONATYPE_PASSWORD")
  } yield Credentials(realm, host, u, p)
  val ivyCredentials = file(sys.props("user.home")) / ".ivy2" / ".credentials"
  envCredentials.toSeq ++ (if (ivyCredentials.canRead) Seq(Credentials(ivyCredentials)) else Nil)
}

pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toArray)

// releaseProcess and the README update step live in release.sbt.

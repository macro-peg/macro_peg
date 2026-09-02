import sbtrelease._
import ReleaseStateTransformations._
import scala.sys.process._

// Rewrites the `libraryDependencies += ...` line in README.md to the version being released
// (or the next snapshot) and commits it.
val updateReadme: State => State = { state =>
  val extracted = Project.extract(state)
  val v = extracted.get(version)
  val org = extracted.get(organization)
  val n = extracted.get(name)
  val baseDir = extracted.get(baseDirectory)
  val readme = "README.md"
  val readmeFile = baseDir / readme
  val newReadme = IO.read(readmeFile).linesIterator.map { line =>
    val matchReleaseOrSnapshot = line.contains("SNAPSHOT") == v.contains("SNAPSHOT")
    if (line.startsWith("libraryDependencies") && matchReleaseOrSnapshot) {
      s"""libraryDependencies += "${org}" %% "${n}" % "$v""""
    } else line
  }.mkString("", "\n", "\n")
  IO.write(readmeFile, newReadme)
  Process(Seq("git", "add", readme), baseDir) ! state.log
  Process(Seq("git", "commit", "-m", "update " + readme), baseDir) ! state.log
  Process(Seq("git", "diff", "HEAD^"), baseDir) ! state.log
  state
}

commands += Command.command("updateReadme")(updateReadme)

val updateReadmeProcess: ReleaseStep = updateReadme

// Releases go to Sonatype Central through sbt 2's built-in support:
// `publishSigned` stages into target/sona-staging (see publishTo in build.sbt),
// then `sonaRelease` uploads the bundle to the Central Portal and releases it.
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  updateReadmeProcess,
  tagRelease,
  releaseStepCommandAndRemaining("publishSigned"),
  releaseStepCommand("sonaRelease"),
  setNextVersion,
  commitNextVersion,
  updateReadmeProcess,
  pushChanges
)

releaseTagName := "releases/" + (ThisBuild / version).value

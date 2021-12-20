import org.renaissance.License
import sbt.Def
import sbt.Package
import sbt.io.RegularFileFilter

import java.nio.file.Paths
import java.util.Properties
import scala.collection._

enablePlugins(GitVersioning)
enablePlugins(GitBranchPrompt)

//
// On startup, install 'tools/pre-push' as git pre-push hook.
//
val setupPrePush = taskKey[Unit]("Installs git pre-push hook.")
ThisBuild / setupPrePush := Utils.installSymlink(
  file("tools") / "pre-push",
  file(".git") / "hooks" / "pre-push",
  sLog.value
)

Global / onLoad := {
  val previousOnLoad = (Global / onLoad).value
  previousOnLoad.andThen(state => "setupPrePush" :: state)
}

//
// Support for distributions with different licenses.
//
val nonGplOnly = SettingKey[Boolean](
  "nonGplOnly",
  "If set to true, then the distribution will not include GPL, EPL and MPL-licensed benchmarks."
)

ThisBuild / nonGplOnly := false

//
// Make the build tasks cancelable.
//
Global / cancelable := true

//
// Common settings
//
ThisBuild / organization := "org.renaissance"

// Explicitly target JDK8 environment.
ThisBuild / Compile / javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
ThisBuild / Compile / scalacOptions += "-target:jvm-1.8"

// Determine project version using 'git describe'.
ThisBuild / git.useGitDescribe := true

lazy val commonSettingsNoScala = Seq(
  // Don't add Scala version to JAR name.
  crossPaths := false,
  // Don't include Scala library as dependency.
  autoScalaLibrary := false
)

val scalaVersion212 = "2.12.15"
val scalaVersion213 = "2.13.7"

addCommandAlias(
  "renaissanceFormat",
  ";renaissance/scalafmt;renaissance/scalafmtSbt"
)

addCommandAlias(
  "renaissanceFormatCheck",
  ";renaissance/scalafmtCheck;renaissance/scalafmtSbtCheck"
)

addCommandAlias("renaissancePackage", ";renaissance/package")
addCommandAlias("renaissanceJmhPackage", ";renaissanceJmh/package")

/**
 * Generates MANIFEST.MF attributes for top-level JAR files.
 *
 * Besides commonly found attributes, this also includes a collection of
 * Add-Opens specifiers required for out of-the-box support for JDK16+ in
 * various benchmarks: als, chi-square, gauss-mix, log-regression,
 * naive-bayes, movie-lens.
 *
 * @see See [[https://github.com/renaissance-benchmarks/renaissance/issues/241]]
 */
val generateManifestAttributesTask = Def.task {
  val addOpensPackages = Seq(
    "java.base/java.lang",
    "java.base/java.lang.invoke",
    "java.base/java.util",
    "java.base/java.nio",
    "java.base/sun.nio.ch",
    "java.management/sun.management",
    "java.management/sun.management.counter",
    "java.management/sun.management.counter.perf"
  )

  Package.ManifestAttributes(
    ("Specification-Title", "Renaissance Benchmark Suite"),
    // Consider Specification-Version to mark sets of active benchmarks
    ("Git-Head-Commit", git.gitHeadCommit.value.getOrElse("unknown")),
    ("Git-Head-Commit-Date", git.gitHeadCommitDate.value.getOrElse("unknown")),
    ("Git-Uncommitted-Changes", git.gitUncommittedChanges.value.toString),
    ("Add-Opens", addOpensPackages.mkString(" "))
  )
}

//
// Subprojects
//

val scalaCollectionCompatVersion = "2.6.0"
val scalaParallelCollectionsVersion = "1.0.4"

lazy val renaissanceCore = (project in file("renaissance-core"))
  .settings(
    commonSettingsNoScala,
    name := "renaissance-core"
  )

val renaissanceHarnessCommonSettings = Seq(
  target := baseDirectory.value / "target" / name.value,
  moduleName := "renaissance-harness",
  libraryDependencies ++= Seq(
    "com.github.scopt" %% "scopt" % "4.0.1",
    "io.spray" %% "spray-json" % "1.3.6"
  ),
  Compile / scalacOptions ++= Seq("-deprecation"),
  Compile / mainClass := Some("org.renaissance.harness.RenaissanceSuite"),
  Compile / packageBin / packageOptions += generateManifestAttributesTask.value
)

lazy val renaissanceHarness213 = (project in file("renaissance-harness"))
  .settings(
    name := "renaissance-harness_2.13",
    scalaVersion := scalaVersion213,
    renaissanceHarnessCommonSettings
  )
  .dependsOn(renaissanceCore % "provided")

lazy val renaissanceHarness212 = (project in file("renaissance-harness"))
  .settings(
    name := "renaissance-harness_2.12",
    scalaVersion := scalaVersion212,
    renaissanceHarnessCommonSettings,
    libraryDependencies ++= Seq(
      // Needed to compile Scala 2.13 collections with Scala 2.12.
      "org.scala-lang.modules" %% "scala-collection-compat" % scalaCollectionCompatVersion
    )
  )
  .dependsOn(renaissanceCore % "provided")

//
// Benchmark subprojects. Each subproject can provide multiple benchmarks
// build around a common set of dependencies.
//

lazy val dummyBenchmarks = (project in file("benchmarks/dummy"))
  .settings(
    commonSettingsNoScala,
    name := "dummy"
  )
  .dependsOn(renaissanceCore % "provided")

lazy val actorsAkkaBenchmarks = (project in file("benchmarks/actors-akka"))
  .settings(
    name := "actors-akka",
    scalaVersion := scalaVersion213,
    libraryDependencies ++= Seq(
      // akka-actor 2.6.x supports Scala 2.12, 2.13
      "com.typesafe.akka" %% "akka-actor" % "2.6.12"
    )
  )
  .dependsOn(renaissanceCore % "provided")

lazy val actorsReactorsBenchmarks = (project in file("benchmarks/actors-reactors"))
  .settings(
    name := "actors-reactors",
    scalaVersion := scalaVersion212
  )
  .dependsOn(
    renaissanceCore % "provided",
    ProjectRef(uri("benchmarks/actors-reactors/reactors"), "reactorsCommonJVM"),
    ProjectRef(uri("benchmarks/actors-reactors/reactors"), "reactorsCoreJVM")
  )

val sparkVersion = "3.2.0"

lazy val apacheSparkBenchmarks = (project in file("benchmarks/apache-spark"))
  .settings(
    name := "apache-spark",
    scalaVersion := scalaVersion213,
    scalacOptions ++= Seq("-deprecation"),
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % sparkVersion,
      "org.apache.spark" %% "spark-sql" % sparkVersion,
      "org.apache.spark" %% "spark-mllib" % sparkVersion
      // Force common versions of other dependencies.
      "org.scala-lang.modules" %% "scala-collection-compat" % scalaCollectionCompatVersion,
      "org.scala-lang.modules" %% "scala-parallel-collections" % scalaParallelCollectionsVersion,
    )
  )
  .dependsOn(renaissanceCore % "provided")

val jnaVersion = "5.10.0"
val slf4jSimpleVersion = "1.7.32"

lazy val databaseBenchmarks = (project in file("benchmarks/database"))
  .settings(
    name := "database",
    scalaVersion := scalaVersion213,
    libraryDependencies ++= Seq(
      "com.github.jnr" % "jnr-posix" % "3.0.29",
      "org.apache.commons" % "commons-math3" % "3.6.1",
      "org.agrona" % "agrona" % "0.9.7",
      "net.openhft" % "zero-allocation-hashing" % "0.6",
      "org.mapdb" % "mapdb" % "3.0.1",
      "com.h2database" % "h2-mvstore" % "1.4.192",
      "net.openhft" % "chronicle-core" % "2.17.2",
      "net.openhft" % "chronicle-bytes" % "2.17.7" exclude ("net.openhft", "chronicle-core"),
      "net.openhft" % "chronicle-threads" % "2.17.1" exclude ("net.openhft", "chronicle-core"),
      "net.openhft" % "chronicle-map" % "3.17.0" excludeAll (
        ExclusionRule("net.openhft", "chronicle-core"),
        ExclusionRule("net.openhft", "chronicle-bytes"),
        ExclusionRule("net.openhft", "chronicle-threads"),
        ExclusionRule("org.slf4j", "slf4j-api")
      ),
      // Force newer JNA to support more platforms/architectures.
      "net.java.dev.jna" % "jna-platform" % jnaVersion,
      // Add simple binding to silence SLF4J warnings.
      "org.slf4j" % "slf4j-simple" % slf4jSimpleVersion
    )
  )
  .dependsOn(renaissanceCore % "provided")

lazy val jdkConcurrentBenchmarks = (project in file("benchmarks/jdk-concurrent"))
  .settings(
    name := "jdk-concurrent",
    scalaVersion := scalaVersion213,
    libraryDependencies ++= Seq(
      "io.jenetics" % "jenetics" % "4.4.0"
    )
  )
  .dependsOn(renaissanceCore % "provided")

lazy val jdkStreamsBenchmarks = (project in file("benchmarks/jdk-streams"))
  .settings(
    name := "jdk-streams",
    scalaVersion := scalaVersion213
  )
  .dependsOn(renaissanceCore % "provided")

lazy val neo4jBenchmarks = (project in file("benchmarks/neo4j"))
  .settings(
    name := "neo4j",
    scalaVersion := scalaVersion212,
    libraryDependencies ++= Seq(
      // neo4j 4.2 does not support 2.13
      "org.neo4j" % "neo4j" % "4.2.4",
      "net.liftweb" %% "lift-json" % "3.4.3",
      // Force newer JNA to support more platforms/architectures.
      "net.java.dev.jna" % "jna" % jnaVersion
    )
  )
  .dependsOn(renaissanceCore % "provided")

lazy val rxBenchmarks = (project in file("benchmarks/rx"))
  .settings(
    name := "rx",
    scalaVersion := scalaVersion213,
    libraryDependencies ++= Seq(
      "io.reactivex" % "rxjava" % "1.3.7"
    )
  )
  .dependsOn(renaissanceCore % "provided")

lazy val scalaDottyBenchmarks = (project in file("benchmarks/scala-dotty"))
  .settings(
    name := "scala-dotty",
    scalaVersion := scalaVersion213,
    scalacOptions += "-Ytasty-reader",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala3-compiler_3" % "3.0.0",
      // The following is required to compile the workload sources.
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      // Force newer JNA to support more platforms/architectures.
      "net.java.dev.jna" % "jna" % jnaVersion
    )
  )
  .dependsOn(renaissanceCore % "provided")

lazy val scalaSatBenchmarks = (project in file("benchmarks/scala-sat"))
  .settings(
    name := "scala-sat",
    scalaVersion := scalaVersion213
  )
  .dependsOn(
    renaissanceCore % "provided",
    RootProject(uri("benchmarks/scala-sat/scala-smtlib")),
    RootProject(uri("benchmarks/scala-sat/cafesat"))
  )

lazy val scalaStdlibBenchmarks = (project in file("benchmarks/scala-stdlib"))
  .settings(
    name := "scala-stdlib",
    scalaVersion := scalaVersion213
  )
  .dependsOn(renaissanceCore % "provided")

lazy val scalaStmBenchmarks = (project in file("benchmarks/scala-stm"))
  .settings(
    name := "scala-stm",
    scalaVersion := scalaVersion212
  )
  .dependsOn(
    renaissanceCore % "provided",
    RootProject(
      uri("benchmarks/scala-stm/scala-stm-library")
    ) % "compile->compile;compile->test"
  )

val finagleVersion = "21.10.0"

lazy val twitterFinagleBenchmarks = (project in file("benchmarks/twitter-finagle"))
  .settings(
    name := "twitter-finagle",
    scalaVersion := scalaVersion213,
    scalacOptions ++= Seq("-deprecation", "-feature"),
    libraryDependencies := Seq(
      "com.twitter" %% "finagle-http" % finagleVersion,
      "com.twitter" %% "finagle-stats" % finagleVersion,
      "com.twitter" %% "finagle-core" % finagleVersion,
      "com.twitter" %% "util-core" % finagleVersion,
      "com.google.guava" % "guava" % "19.0",
      "org.scala-lang.modules" %% "scala-parallel-collections" % scalaParallelCollectionsVersion,
      // Add simple binding to silence SLF4J warnings.
      "org.slf4j" % "slf4j-simple" % slf4jSimpleVersion
      // Force common versions of other dependencies.
      "org.scala-lang.modules" %% "scala-collection-compat" % scalaCollectionCompatVersion
    )
  )
  .dependsOn(renaissanceCore % "provided")

//
// Project collections.
//

/**
 * The [[renaissanceBenchmarks]] collection contains only projects that
 * provide benchmarks. It needs to be updated whenever a new benchmark
 * project is added (which is not that common).
 */
val renaissanceBenchmarks: Seq[Project] = Seq(
  dummyBenchmarks,
  actorsAkkaBenchmarks,
  actorsReactorsBenchmarks,
  apacheSparkBenchmarks,
  databaseBenchmarks,
  jdkConcurrentBenchmarks,
  jdkStreamsBenchmarks,
  neo4jBenchmarks,
  rxBenchmarks,
  scalaDottyBenchmarks,
  scalaSatBenchmarks,
  scalaStdlibBenchmarks,
  scalaStmBenchmarks,
  twitterFinagleBenchmarks
)

/**
 * The [[aggregateProjects]] collection does not include [[renaissanceCore]],
 * because the build (meta) project depends on it and running the aggregate
 * 'clean' task on the [[renaissance]] (root) project would break the build.
 */
val aggregateProjects =
  renaissanceBenchmarks ++ Seq(renaissanceHarness213, renaissanceHarness212)

/**
 * The [[renaissanceModules]] collection contains projects that represent
 * modules, i.e., they will have an entry in the 'modules.properties' file
 * and the final fat JAR will contain a directory with their dependencies.
 */
val renaissanceModules = aggregateProjects :+ renaissanceCore

/**
 * Creates a task that collects the runtime dependency jars for the given
 * projects. For each project, we need to create a separate tasks to query
 * the project settings, because these can be only evaluated in a task. The
 * obvious approach of subjecting the input sequence to a mapping function
 * cannot be used in SBT at this level.
 *
 * The task produces a sequence of tuples with the following structure:
 * (project name, runtime jars, scala version)
 */
def collectModulesDetailsTask(projects: Seq[Project]) =
  Tasks.collect(projects.map { project =>
    // Create a task to produce output tuple for a specific project.
    Def.task {
      val projectName = (project / name).value
      val projectJars = (project / Runtime / dependencyClasspathAsJars).value.map(_.data)
      val projectScala = CrossVersion.binaryScalaVersion((project / scalaVersion).value)
      (projectName, projectJars, projectScala)
    }
  })

/**
 * Generates module metadata file with the given name and for
 * the given modules as a managed compilation resource.
 */
def generateModulesMetadataTask(modules: Seq[Project], baseName: String) =
  Def.task {
    val log = sLog.value

    val modulesDetails = collectModulesDetailsTask(modules).value

    val modulesProps = new Properties
    mapModuleJarsToAssemblyEntries(modulesDetails).foreach {
      case (moduleName, moduleJarEntries) =>
        val jarLine = moduleJarEntries.map { case (_, jarEntry) => jarEntry }.mkString(",")
        modulesProps.setProperty(moduleName, jarLine)
    }

    val outputFile = (Compile / resourceManaged).value / baseName

    log.info(s"Writing $outputFile ...")
    Seq(Utils.storeProperties(modulesProps, "Module jars", outputFile))
  }

/**
 * Generates benchmark metadata file with the given name and for
 * the given benchmarks as a managed compilation resource.
 */
def generateBenchmarksMetadataTask(modules: Seq[Project], baseName: String) =
  Def.task {
    val log = sLog.value

    val benchmarksProps = new Properties
    collectModulesDetailsTask(modules).value.foreach {
      case (moduleName, moduleJars, binaryVersion) =>
        for (bench <- Benchmarks.listBenchmarks(moduleName, moduleJars, Some(log))) {
          if (!nonGplOnly.value || bench.distro == License.MIT) {
            benchmarksProps.setProperty(s"benchmark.${bench.name}.scala_version", binaryVersion)
            for ((k, v) <- bench.toMap) {
              benchmarksProps.setProperty(s"benchmark.${bench.name}.$k", v)
            }
          }
        }
    }

    val outputFile = (Compile / resourceManaged).value / baseName

    log.info(s"Writing $outputFile ...")
    Seq(Utils.storeProperties(benchmarksProps, "Benchmark details", outputFile))
  }

def mapModuleJarsToAssemblyEntries(modulesDetails: Seq[(String, Seq[File], String)]) = {
  //
  // Convert a collection of modules referencing dependency jars into
  // a collection of dependency jars referencing modules that use them.
  //
  // (m1, (j1, j2)), (m2, (j1, j3)), ... =>
  // (m1, j1), (m1, j2), (m2, j1), (m2, j3), ... ->
  // j1 -> ((m1, j1), (m2, j1)), j2 -> ((m1, j2)), j3 -> ((m2, j3)), ...
  //
  val jarModules: Map[String, Seq[(String, File)]] = modulesDetails
    .flatMap {
      case (module, jars, _) => jars.map { jar => (module, jar) }
    }
    .groupBy { case (_, jar) => jar.getName }

  //
  // Map each module jar to an entry in the assembly.
  // Jars used by multiple modules go into shared directory.
  // Jars used by a single module go into module-specific directory.
  //
  val shared = Paths.get("shared")
  val unique = Paths.get("unique")

  val jarEntries = jarModules.flatMap {
    case (jarBaseName, modules) =>
      modules.map {
        case (name, jarPath) =>
          val entryDir = if (modules.length < 2) unique.resolve(name) else shared
          val entryPath = entryDir.resolve(jarBaseName)
          // Jar entry needs to use Unix path separator (even on Windows).
          jarPath -> Utils.asUnixPath(entryPath)
      }
  }

  //
  // Associate module jars with assembly jar entries
  // in the original collection (grouped by module).
  // This is basically a join on the jar source path.
  //
  modulesDetails.map {
    case (name, jars, _) =>
      name -> jars.map(srcJar => (srcJar, jarEntries(srcJar)))
  }
}

def mapModuleDependencyJarsToAssemblyTask(modules: Seq[Project]) =
  Def.task[Seq[(File, String)]] {
    val modulesDetails = collectModulesDetailsTask(modules).value
    mapModuleJarsToAssemblyEntries(modulesDetails).flatMap(_._2).distinct
  }

/**
 * Generates assembly mappings for all class files on the given classpath.
 * The class directory hierarchy is mapped to the root of the assembly so
 * that the classes are directly available to the JVM.
 */
def mapClassesToAssemblyTask(classpath: Classpath) =
  Def.task[Seq[(File, String)]] {
    classpath.map(_.data).filter(_.isDirectory).flatMap { dir =>
      //
      // For all files below the class path directory, the destination
      // in the package is the relative part of the path (with respect
      // to the classpath directory).
      //
      val filePaths = (dir ** (-DirectoryFilter)).get()
      filePaths.pair(_.relativeTo(dir)).map {
        // Jar entry needs to use Unix path separator (even on Windows).
        case (src, rel) => src -> Utils.asUnixPath(rel.toPath)
      }
    }
  }

/**
 * This is the root project. The tasks that generate metadata files and
 * the final bundle depend on [[renaissanceModules]] which contains the
 * harness and the benchmark projects. The evaluation of those tasks will
 * trigger the compilation of the modules.
 */
lazy val renaissance = (project in file("."))
  .settings(
    commonSettingsNoScala,
    name := "renaissance",
    // Reflect the distribution license in the package name.
    moduleName := name.value + "-" + (if (nonGplOnly.value) "mit" else "gpl"),
    inConfig(Compile)(
      Seq(
        // The main class for the JAR is the Launcher from the core package.
        mainClass := (renaissanceCore / Compile / mainClass).value,
        // Generate benchmark metadata used by the launcher/harness.
        resourceGenerators ++= Seq(
          generateModulesMetadataTask(renaissanceModules, "modules.properties").taskValue,
          generateBenchmarksMetadataTask(
            renaissanceBenchmarks,
            "benchmarks.properties"
          ).taskValue
        ),
        // Set additional manifest attributes, especially Add-Opens.
        packageBin / packageOptions += generateManifestAttributesTask.value,
        // Include core classes directly in the output JAR file.
        packageBin / mappings ++= Def.taskDyn {
          val classpath = internalDependencyClasspath.value
          mapClassesToAssemblyTask(classpath)
        }.value,
        // Include dependency JAR files in the output JAR.
        packageBin / mappings ++= mapModuleDependencyJarsToAssemblyTask(
          renaissanceModules
        ).value
      )
    )
  )
  // The bundle directly depends only on the 'renaissance-core' classes.
  .dependsOn(renaissanceCore)
  // Aggregate other modules for selected tasks.
  .aggregate(aggregateProjects.map { _.project }: _*)
  .settings(
    aggregate := false,
    clean / aggregate := true,
    scalafmt / aggregate := true
  )

//
// JMH support
//

/**
 * Generates JMH wrappers for Renaissance benchmarks in the given modules.
 * Each wrapper is derived from a common base class and includes just the
 * benchmark-specific information needed to run the benchmark under JMH.
 * The task returns a collection of generated source files.
 */
def generateJmhWrappersTask(modules: Seq[Project]) =
  Def.task {
    val log = sLog.value

    // Delete all subdirectories in the output directory.
    val outputDir = (Compile / sourceManaged).value
    log.debug(s"Deleting JMH wrappers in $outputDir")
    IO.delete(outputDir)

    collectModulesDetailsTask(modules).value.flatMap {
      case (moduleName, moduleJars, _) =>
        for {
          bench <- Benchmarks.listBenchmarks(moduleName, moduleJars, None)
          if (!nonGplOnly.value || bench.distro == License.MIT) &&
            (!bench.groups.contains("dummy") || bench.name == "dummy-empty")
        } yield {
          log.info(s"Generating JMH wrappers for ${bench.name}")
          RenaissanceJmh.generateJmhWrapperBenchmarkClass(bench, outputDir.toPath)
        }
    }
  }

/**
 * Generates JMH sources and resources for the generated benchmark wrappers.
 * Because the JMH generator produces sources and resources at the same time,
 * the task returns two corresponding file collections.
 */
def generateJmhSourcesResourcesTask(
  wrappers: Project
): Def.Initialize[Task[(Seq[File], Seq[File])]] =
  Def
    .task {
      val log = sLog.value

      val inputBytecodeDir = (wrappers / Compile / classDirectory).value
      val outputSourceDir = (Compile / sourceManaged).value
      val outputResourceDir = (Compile / resourceManaged).value

      val jmhMainClass = "org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator"
      val jmhClasspath = (Compile / dependencyClasspath).value.map(_.data)
      val jmhOptions = Seq(inputBytecodeDir, outputSourceDir, outputResourceDir).map(_.toString)

      log.debug(
        s"Running JMH generator...\n\toptions: $jmhOptions\n\tclasspath: $jmhClasspath"
      )
      val sbtRun = new Run(scalaInstance.value, true, taskTemporaryDirectory.value)
      sbtRun.run(jmhMainClass, jmhClasspath, jmhOptions, sLog.value).get

      // Return sources and resources separately.
      val sourceFiles = (outputSourceDir ** RegularFileFilter).get
      val resourceFiles = (outputResourceDir ** RegularFileFilter).get
      (sourceFiles, resourceFiles)
    }

/**
 * Generates assembly mappings for the contents of JAR files on the given
 * classpath. Each JAR file is extracted to a separate directory and all
 * files from that directory (except the manifest) are mapped to the root
 * of the final assembly.
 */
def mapJarContentsToAssemblyTask(classpath: Classpath) =
  Def.task[Seq[(File, String)]] {
    val log = sLog.value

    val outputDir = target.value / "jar_contents"
    classpath.map(_.data).filter(_.isFile).flatMap { jar =>
      // Extract the JAR file.
      val jarOutputDir = outputDir / jar.getName
      IO.delete(jarOutputDir)
      log.debug(s"Extracting $jar => $jarOutputDir")
      IO.unzip(jar, jarOutputDir, "*", preserveLastModified = true)

      // Get all files except the manifest.
      val allFiles = jarOutputDir ** RegularFileFilter
      val manifestFile = jarOutputDir / "META-INF" / "MANIFEST.MF"
      val includedFiles = allFiles --- manifestFile

      // Map the files to the output JAR.
      includedFiles
        .pair(_.relativeTo(jarOutputDir))
        .map {
          // Jar entry needs to use Unix path separator (even on Windows).
          case (src, rel) => src -> Utils.asUnixPath(rel.toPath)
        }
    }
  }

val jmhVersion = "1.33"

/**
 * This project generates JMH wrappers for Renaissance benchmarks. The
 * compiled wrappers are used by the [[renaissanceJmh]] project below.
 */
lazy val renaissanceJmhWrappers = (project in file("renaissance-jmh/wrappers"))
  .settings(
    name := "renaissance-jmh-wrappers",
    commonSettingsNoScala,
    libraryDependencies := Seq(
      "org.openjdk.jmh" % "jmh-core" % jmhVersion
    ),
    Compile / sourceGenerators += generateJmhWrappersTask(renaissanceBenchmarks).taskValue
  )
  // We need the module and benchmark metadata in addition to core classes.
  .dependsOn(renaissance)

/**
 * This project provides support for running Renaissance under JMH. It
 * generates JMH benchmarks and resources using the JMH wrappers from the
 * [[renaissanceJmhWrappers]] project above. The final bundle then needs
 * to include all the generated classes and JMH classes directly (including
 * its dependencies), along with the benchmark dependencies as JAR files.
 */
lazy val renaissanceJmh = (project in file("renaissance-jmh"))
  .settings(
    name := "renaissance-jmh",
    commonSettingsNoScala,
    libraryDependencies := Seq(
      "org.openjdk.jmh" % "jmh-generator-bytecode" % jmhVersion,
      "org.openjdk.jmh" % "jmh-generator-reflection" % jmhVersion
    ),
    inConfig(Compile)(
      Seq(
        // Split result from the JMH generator task between sources and resources.
        sourceGenerators += Def.task {
          generateJmhSourcesResourcesTask(renaissanceJmhWrappers).value._1
        }.taskValue,
        resourceGenerators += Def.task {
          generateJmhSourcesResourcesTask(renaissanceJmhWrappers).value._2
        }.taskValue,
        // The main class for the JAR is the JMH launcher.
        mainClass := Some("org.openjdk.jmh.Main"),
        // Generate benchmark metadata used by the launcher/harness.
        packageBin / packageOptions += generateManifestAttributesTask.value,
        // Include classes from internal dependencies directly.
        packageBin / mappings ++= Def.taskDyn {
          val classpath = internalDependencyClasspath.value
          mapClassesToAssemblyTask(classpath)
        }.value,
        // Include the contents of JAR files of compile dependencies.
        packageBin / mappings ++= Def.taskDyn {
          val classpath = (renaissanceJmhWrappers / Compile / externalDependencyClasspath).value
          mapJarContentsToAssemblyTask(classpath)
        }.value,
        // Include benchmark dependency JAR files in the output JAR.
        packageBin / mappings ++= mapModuleDependencyJarsToAssemblyTask(
          renaissanceModules
        ).value
      )
    )
  )
  .dependsOn(renaissanceJmhWrappers)

import QuineSettings._
import Dependencies._

ThisBuild / resolvers += Resolver.url(
  "thatDot ivy",
  url("https://s3.us-west-2.amazonaws.com/com.thatdot.dependencies/")
)(Resolver.ivyStylePatterns)
ThisBuild / resolvers += "thatDot maven" at "https://s3.us-west-2.amazonaws.com/com.thatdot.dependencies/release/"

ThisBuild / scalaVersion := scalaV

addCommandAlias("fixall", "; scalafixAll; scalafmtAll; scalafmtSbt")

//ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / evictionErrorLevel := Level.Info

// Core streaming graph interpreter
lazy val `quine-core`: Project = project
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.chuusai" %% "shapeless" % shapelessV,
      "com.google.guava" % "guava" % guavaV,
      "org.scala-lang.modules" %% "scala-collection-compat" % scalaCollectionCompatV,
      "org.scala-lang.modules" %% "scala-java8-compat" % scalaJava8CompatV,
      "org.apache.pekko" %% "pekko-actor" % pekkoV,
      "org.apache.pekko" %% "pekko-stream" % pekkoStreamV,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoV,
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingV,
      "io.dropwizard.metrics" % "metrics-core" % dropwizardMetricsV,
      "io.circe" %% "circe-parser" % circeV,
      "org.msgpack" % "msgpack-core" % msgPackV,
      "org.apache.commons" % "commons-text" % commonsTextV,
      "com.47deg" %% "memeid4s" % memeIdV,
      // Testing
      "org.scalatest" %% "scalatest" % scalaTestV % Test,
      "org.scalacheck" %% "scalacheck" % scalaCheckV % Test,
      "org.scalatestplus" %% "scalacheck-1-17" % scalaTestScalaCheckV % Test,
      "com.softwaremill.diffx" %% "diffx-scalatest-should" % diffxV % Test,
      "ch.qos.logback" % "logback-classic" % logbackV % Test,
      "commons-io" % "commons-io" % commonsIoV % Test,
      "org.typelevel" %% "cats-core" % catsV,
      "org.typelevel" %% "cats-effect" % catsEffectV,
      "org.antlr" % "antlr4" % antlrV,
      "org.typelevel" %% "cats-parse" % catsParseV,
      "com.thatdot" %% "query-language" % quineQueryV
    ),
    // Compile different files depending on scala version
    Compile / unmanagedSourceDirectories += {
      (Compile / sourceDirectory).value / "scala-2.13"
    },
    addCompilerPlugin("org.typelevel" %% "kind-projector" % kindProjectorV cross CrossVersion.full)
  )
  .enablePlugins(BuildInfoPlugin, FlatcPlugin)
  .settings(
    buildInfoOptions := Seq(BuildInfoOption.BuildTime),
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      git.gitHeadCommit,
      git.gitUncommittedChanges,
      git.gitHeadCommitDate,
      BuildInfoKey.action("javaVmName")(scala.util.Properties.javaVmName),
      BuildInfoKey.action("javaVendor")(scala.util.Properties.javaVendor),
      BuildInfoKey.action("javaVersion")(scala.util.Properties.javaVersion)
    ),
    buildInfoPackage := "com.thatdot.quine"
  )

// MapDB implementation of a Quine persistor
lazy val `quine-mapdb-persistor`: Project = project
  .settings(commonSettings)
  .dependsOn(`quine-core` % "compile->compile;test->test")
  .settings(
    /* `net.jpountz.lz4:lz4` was moved to `org.lz4:lz4-java`, but MapDB hasn't
     * adapted to this change quickly. However, since other parts of the Java
     * ecosystem _have_ (example: `pekko-connectors-kafka`), we need to exclude the
     * bad JAR and explicitly pull in the good one.
     */
    libraryDependencies ++= Seq(
      "com.softwaremill.diffx" %% "diffx-scalatest-should" % diffxV % Test,
      ("org.mapdb" % "mapdb" % mapDbV).exclude("net.jpountz.lz4", "lz4"),
      "org.lz4" % "lz4-java" % lz4JavaV
    )
  )

// RocksDB implementation of a Quine persistor
lazy val `quine-rocksdb-persistor`: Project = project
  .settings(commonSettings)
  .dependsOn(`quine-core` % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.diffx" %% "diffx-scalatest-should" % diffxV % Test,
      "org.rocksdb" % "rocksdbjni" % rocksdbV
    )
  )

// Cassandra implementation of a Quine persistor
lazy val `quine-cassandra-persistor`: Project = project
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(commonSettings)
  .dependsOn(`quine-core` % "compile->compile;it->test")
  .enablePlugins(spray.boilerplate.BoilerplatePlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.diffx" %% "diffx-scalatest-should" % diffxV % Test,
      "org.typelevel" %% "cats-core" % catsV,
      "com.datastax.oss" % "java-driver-query-builder" % cassandraClientV exclude ("com.github.stephenc.jcip", "jcip-annotations"),
      "software.aws.mcs" % "aws-sigv4-auth-cassandra-java-driver-plugin" % "4.0.9" exclude ("com.github.stephenc.jcip", "jcip-annotations"),
      "software.amazon.awssdk" % "sts" % awsSdkV,
      "com.github.nosan" % "embedded-cassandra" % embeddedCassandraV % IntegrationTest
    )
  )

// Parser and interepreter for a subset of [Gremlin](https://tinkerpop.apache.org/gremlin.html)
lazy val `quine-gremlin`: Project = project
  .settings(commonSettings)
  .dependsOn(`quine-core`)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-parser-combinators" % scalaParserCombinatorsV,
      "org.apache.commons" % "commons-text" % commonsTextV,
      "org.scalatest" %% "scalatest" % scalaTestV % Test
    )
  )

// Compiler for compiling [Cypher](https://neo4j.com/docs/cypher-manual/current/) into Quine queries
lazy val `quine-cypher`: Project = project
  .settings(commonSettings)
  .dependsOn(`quine-core` % "compile->compile;test->test")
  .settings(
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-Xlog-implicits"
    ),
    libraryDependencies ++= Seq(
      "com.thatdot.opencypher" %% "expressions" % openCypherV,
      "com.thatdot.opencypher" %% "front-end" % openCypherV,
      "com.thatdot.opencypher" %% "opencypher-cypher-ast-factory" % openCypherV,
      "com.thatdot.opencypher" %% "util" % openCypherV,
      "commons-codec" % "commons-codec" % commonsCodecV,
      "org.typelevel" %% "cats-core" % catsV,
      "org.scalatest" %% "scalatest" % scalaTestV % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoV % Test
    ),
    addCompilerPlugin("org.typelevel" % "kind-projector" % kindProjectorV cross CrossVersion.full),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % betterMonadicForV)
  )

/*
 * Version 7.5.1. It is expected that `Network` and `DataSet` are available under
 * A globally available `vis` object, as with
 *
 * ```html
 * <script
 *   type="text/javascript"
 *   src="https://unpkg.com/vis-network/standalone/umd/vis-network.min.js"
 * ></script>
 * ```
 *
 * Thanks to [`scala-js-ts-importer`][ts-importer] which made it possible to generate
 * A first pass of the facade directly from the Typescipt bindings provided with
 * `vis-network` (see `Network.d.ts`).
 *
 * [ts-importer]: https://github.com/sjrd/scala-js-ts-importer
 * [visjs]: https://github.com/visjs/vis-network
 */
lazy val `visnetwork-facade`: Project = project
  .settings(commonSettings)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % scalajsDomV
    )
  )

// REST API specifications for `quine`-based applications
lazy val `quine-endpoints` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("quine-endpoints"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.endpoints4s" %%% "json-schema-generic" % endpoints4sDefaultV,
      "org.endpoints4s" %%% "json-schema-circe" % "2.3.0",
      "io.circe" %% "circe-core" % circeV,
      "org.endpoints4s" %%% "openapi" % endpoints4sOpenapiV,
      "com.lihaoyi" %% "ujson-circe" % ujsonV, // For the OpenAPI rendering
      "org.scalacheck" %%% "scalacheck" % scalaCheckV % Test
    )
  )
  .jsSettings(
    // Provides an implementatAllows us to use java.time.Instant in Scala.js
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTimeV
  )

// Quine web application
lazy val `quine-browser`: Project = project
  .settings(commonSettings, slinkySettings)
  .dependsOn(`quine-endpoints`.js, `visnetwork-facade`)
  .enablePlugins(ScalaJSBundlerPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % scalajsDomV,
      "org.scala-js" %%% "scala-js-macrotask-executor" % scalajsMacroTaskExecutorV,
      "org.endpoints4s" %%% "xhr-client" % endpoints4sXhrClientV
    ),
    Compile / npmDevDependencies ++= Seq(
      "ts-loader" -> "8.0.0",
      "typescript" -> "4.9.5",
      "@types/react" -> "17.0.0",
      "@types/react-dom" -> "17.0.0",
      "@types/node" -> "16.7.13"
    ),
    Compile / npmDependencies ++= Seq(
      "react" -> reactV,
      "react-dom" -> reactV,
      "es6-shim" -> "0.35.7",
      "react-plotly.js" -> reactPlotlyV,
      "plotly.js" -> plotlyV,
      "@stoplight/elements" -> stoplightElementsV,
      "mkdirp" -> "1.0.0"
    ),
    webpackNodeArgs := nodeLegacySslIfAvailable,
    // Scalajs-bundler 0.21.1 updates to webpack 5 but doesn't inform webpack that the scalajs-based file it emits is
    // an entrypoint -- therefore webpack emits an error saying effectively, "no entrypoint" that we must ignore.
    // This aggressively ignores all warnings from webpack, which is more than necessary, but trivially works
    webpackExtraArgs := Seq("--ignore-warnings-message", "/.*/"),
    fastOptJS / webpackConfigFile := Some(baseDirectory.value / "dev.webpack.config.js"),
    fastOptJS / webpackDevServerExtraArgs := Seq("--inline", "--hot"),
    fullOptJS / webpackConfigFile := Some(baseDirectory.value / "prod.webpack.config.js"),
    Test / webpackConfigFile := Some(baseDirectory.value / "common.webpack.config.js"),
    test := {},
    useYarn := true,
    yarnExtraArgs := Seq("--frozen-lockfile")
  )

// Streaming graph application built on top of the Quine library
lazy val `quine`: Project = project
  .settings(commonSettings)
  .dependsOn(
    `quine-core` % "compile->compile;test->test",
    `quine-cypher`,
    `quine-endpoints`.jvm % "compile->compile;test->test",
    `quine-gremlin`,
    `quine-cassandra-persistor`,
    `quine-mapdb-persistor`,
    `quine-rocksdb-persistor`
  )
  .settings(
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % logbackV,
      "com.github.davidb" % "metrics-influxdb" % metricsInfluxdbV,
      "com.github.jnr" % "jnr-posix" % jnrPosixV,
      "com.github.pjfanning" %% "aws-spi-pekko-http" % "0.1.0",
      "com.github.pjfanning" %% "pekko-http-circe" % "2.3.4",
      "com.github.pureconfig" %% "pureconfig" % pureconfigV,
      "com.github.scopt" %% "scopt" % scoptV,
      "com.google.api.grpc" % "proto-google-common-protos" % protobufCommonV,
      "com.google.guava" % "guava" % guavaV,
      "com.google.protobuf" % "protobuf-java" % protobufV,
      "commons-io" % "commons-io" % commonsIoV,
      "io.circe" %% "circe-config" % "0.10.1",
      "io.circe" %% "circe-generic-extras" % "0.14.3",
      "io.circe" %% "circe-yaml-v12" % "0.15.1",
      "io.dropwizard.metrics" % "metrics-core" % dropwizardMetricsV,
      "io.dropwizard.metrics" % "metrics-jmx" % dropwizardMetricsV,
      "io.dropwizard.metrics" % "metrics-jvm" % dropwizardMetricsV,
      "org.apache.kafka" % "kafka-clients" % kafkaClientsV,
      "org.apache.pekko" %% "pekko-connectors-csv" % pekkoConnectorsV,
      "org.apache.pekko" %% "pekko-connectors-kafka" % pekkoKafkaV,
      "org.apache.pekko" %% "pekko-connectors-kinesis" % pekkoConnectorsV exclude ("org.rocksdb", "rocksdbjni"),
      // 5 Next deps: override outdated pekko-connectors-kinesis dependencies
      "software.amazon.kinesis" % "amazon-kinesis-client" % amazonKinesisClientV,
      "software.amazon.glue" % "schema-registry-serde" % amazonGlueV,
      "com.amazonaws" % "aws-java-sdk-sts" % awsSdkv1V,
      "org.apache.commons" % "commons-compress" % apacheCommonsCompressV,
      "com.github.erosb" % "everit-json-schema" % "1.14.4",
      "org.apache.pekko" %% "pekko-connectors-s3" % pekkoConnectorsV,
      "org.apache.pekko" %% "pekko-connectors-sns" % pekkoConnectorsV,
      "org.apache.pekko" %% "pekko-connectors-sqs" % pekkoConnectorsV,
      "org.apache.pekko" %% "pekko-connectors-sse" % pekkoConnectorsV,
      "org.apache.pekko" %% "pekko-connectors-text" % pekkoConnectorsV,
      // pekko-http-xml is not a direct dep, but pulled in transitively by connector modules above.
      // All pekko-http module version numbers need to match exactly, or else it
      // throws at startup: "java.lang.IllegalStateException: Detected possible incompatible versions on the classpath."
      "org.apache.pekko" %% "pekko-http-xml" % pekkoHttpV,
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoV % Test,
      "org.endpoints4s" %% "pekko-http-server" % endpoints4sHttpServerV,
      "org.jetbrains.kotlin" % "kotlin-stdlib" % kotlinStdlibV,
      "org.jetbrains.kotlin" % "kotlin-stdlib-jdk8" % kotlinStdlibV,
      "org.scalatest" %% "scalatest" % scalaTestV % Test,
      "org.scalatestplus" %% "scalacheck-1-17" % scalaTestScalaCheckV % Test,
      "org.snakeyaml" % "snakeyaml-engine" % snakeYamlV,
      // WebJars (javascript dependencies masquerading as JARs)
      "org.webjars" % "bootstrap" % bootstrapV,
      "org.webjars" % "ionicons" % ioniconsV,
      "org.webjars" % "jquery" % jqueryV,
      "org.webjars" % "webjars-locator" % webjarsLocatorV,
      "org.webjars.bowergithub.plotly" % "plotly.js" % plotlyV,
      "org.webjars.npm" % "sugar-date" % sugarV,
      "org.webjars.npm" % "vis-network" % visNetworkV,
      "org.xerial.snappy" % "snappy-java" % snappyV
    )
  )
  .enablePlugins(WebScalaJSBundlerPlugin)
  .settings(
    scalaJSProjects := Seq(`quine-browser`),
    Assets / pipelineStages := Seq(scalaJSPipeline)
  )
  .enablePlugins(BuildInfoPlugin, Packaging, Docker, Ecr)
  .settings(
    startupMessage := "",
    buildInfoKeys := Seq[BuildInfoKey](version, startupMessage),
    buildInfoPackage := "com.thatdot.quine.app"
  )

// Files under quine-docs/src/main/paradox/lib have been manually added. When we moved from
// the  sbt-paradox-material-theme (see plugins.sbt) to a fork, the
// forked library omitted this directory, so those files have been
// copied from the original plugin library, however, they should _not_ be
// included in the paradox theme.
lazy val `quine-docs`: Project = {
  val docJson = Def.task((Compile / paradox / sourceManaged).value / "reference" / "openapi.json")
  val cypherTable1 = Def.task((Compile / paradox / sourceManaged).value / "reference" / "cypher-builtin-functions.md")
  val cypherTable2 =
    Def.task((Compile / paradox / sourceManaged).value / "reference" / "cypher-user-defined-functions.md")
  val cypherTable3 =
    Def.task((Compile / paradox / sourceManaged).value / "reference" / "cypher-user-defined-procedures.md")
  val recipesFolder =
    Def.task((Compile / paradox / sourceManaged).value / "recipes")
  Project("quine-docs", file("quine-docs"))
    .dependsOn(`quine`)
    .settings(commonSettings)
    .enablePlugins(ParadoxThatdot, GhpagesPlugin)
    .settings(
      projectName := "Quine",
      git.remoteRepo := "git@github.com:thatdot/quine.io.git",
      ghpagesBranch := "main",
      ghpagesCleanSite / excludeFilter := { (f: File) =>
        (ghpagesRepository.value / "CNAME").getCanonicalPath == f.getCanonicalPath
      },
      // Same as `paradox` itself
      libraryDependencies ++= Seq(
        "org.pegdown" % "pegdown" % pegdownV,
        "org.parboiled" % "parboiled-java" % parboiledV
      ),
      Compile / paradoxProperties ++= Map(
        "snip.github_link" -> "false",
        "snip.quine.base_dir" -> (`quine` / baseDirectory).value.getAbsolutePath,
        "material.repo" -> "https://github.com/thatdot/quine",
        "material.repo.type" -> "github",
        "material.social" -> "https://that.re/quine-slack",
        "material.social.type" -> "slack",
        "include.generated.base_dir" -> (Compile / paradox / sourceManaged).value.toString,
        "project.name" -> projectName.value,
        "logo.link.title" -> "Quine",
        "quine.jar" -> s"quine-${version.value}.jar"
      ),
      description := "Quine is a streaming graph interpreter meant to trigger actions in real-time based on complex patterns pulled from high-volume streaming data",
      Compile / paradoxMarkdownToHtml / sourceGenerators += Def.taskDyn {
        (Compile / runMain)
          .toTask(s" com.thatdot.quine.docs.GenerateOpenApi ${docJson.value.getAbsolutePath}")
          .map(_ => Seq()) // return no files because files returned are supposed to be markdown
      },
      // Register the `openapi.json` file here
      Compile / paradox / mappings ++= List(
        docJson.value -> "reference/openapi.json"
      ),
      // ---
      // Uncomment to build the recipe template pages
      // then add * @ref:[Recipes](recipes/index.md) into docs.md
      // ---
      //Compile / paradoxMarkdownToHtml / sourceGenerators += Def.taskDyn {
      //  val inDir: File = (quine / baseDirectory).value / "recipes"
      //  val outDir: File = (Compile / paradox / sourceManaged).value / "recipes"
      //  (Compile / runMain)
      //    .toTask(s" com.thatdot.quine.docs.GenerateRecipeDirectory ${inDir.getAbsolutePath} ${outDir.getAbsolutePath}")
      //    .map(_ => (outDir * "*.md").get)
      //},
      Compile / paradoxNavigationDepth := 3,
      Compile / paradoxNavigationExpandDepth := Some(3),
      paradoxRoots := List("index.html", "docs.html", "about.html", "download.html"),
      Compile / paradoxMarkdownToHtml / sourceGenerators += Def.taskDyn {
        (Compile / runMain)
          .toTask(
            List(
              " com.thatdot.quine.docs.GenerateCypherTables",
              cypherTable1.value.getAbsolutePath,
              cypherTable2.value.getAbsolutePath,
              cypherTable3.value.getAbsolutePath
            ).mkString(" ")
          )
          .map(_ => Nil) // files returned are included, not top-level
      },
      Compile / paradoxMaterialTheme ~= {
        _.withCustomStylesheet("assets/quine.css")
          .withLogo("assets/images/quine_logo.svg")
          .withColor("white", "quine-blue")
          .withFavicon("assets/images/favicon.svg")
      },
      Compile / overlayDirectory := (`paradox-overlay` / baseDirectory).value
    )
}

lazy val `paradox-overlay`: Project = project

// Spurious warnings
Global / excludeLintKeys += `quine-docs` / Paradox / paradoxNavigationExpandDepth
Global / excludeLintKeys += `quine-docs` / Paradox / paradoxNavigationDepth
Global / excludeLintKeys += `quine-browser` / webpackNodeArgs
Global / excludeLintKeys += `quine-browser` / webpackExtraArgs

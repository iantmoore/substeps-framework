package org.substeps.report

import java.{io, util}
import java.io.{BufferedWriter, File, FileNotFoundException, IOException}
import java.net.{JarURLConnection, URISyntaxException, URL}
import java.nio.charset.Charset
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneId}
import java.util.Collections

import com.google.common.io.{FileWriteMode, Files}
import com.google.gson.reflect.TypeToken
import com.technophobia.substeps.model.exception.SubstepsException
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.commons.io.{FileUtils, IOUtils}
import org.apache.commons.lang3.{StringEscapeUtils, StringUtils}
import org.json4s._
import org.json4s.native.Serialization
import org.json4s.native.Serialization._
import org.slf4j.{Logger, LoggerFactory}
import org.substeps.config.SubstepsConfigLoader
import org.substeps.runner.NewSubstepsExecutionConfig

import scala.beans.BeanProperty

abstract class RootNodeDescriptionProvider{

   def describe(config : Config) : String
}

class DefaultDescriptionProvider extends RootNodeDescriptionProvider{
  override def describe(config: Config): String = {
    config.getString("org.substeps.executionConfig.description")
  }
}

object ReportBuilder {
  def openStateFor(result: String) = {

    result match {
      case "CHILD_FAILED" =>     Map ("state" -> "open")
      case "FAILED" => Map ("state" -> "open")
      case "NON_CRITICAL_FAILURE"  => Map ("state" -> "open")
      case _ => Map()
    }

  }

  def iconFor(result: String): String = {
    icons.getOrElse(result, "PARSE_FAILURE")
  }



  val icons = Map("PASSED" -> "PASSED",
    "NOT_RUN" -> "NOT_RUN",
    "PARSE_FAILURE" -> "PARSE_FAILURE",
    "FAILED" -> "FAILED",
    "CHILD_FAILED" -> "CHILD_FAILED",
    "NON_CRITICAL_FAILURE" -> "NON_CRITICAL_FAILURE")


  /* TODO
  full range of states:
  from ExecutionResult
      IGNORED(false), NOT_INCLUDED(false), NOT_RUN(false), RUNNING(false), PASSED(false), FAILED(true), NON_CRITICAL_FAILURE(false), PARSE_FAILURE(true),
    SETUP_TEARDOWN_FAILURE(true), CHILD_FAILED(true);
  */

  // total, run, passed, failed, skipped
  val resultToCounters = Map("PASSED" -> Counters.build(1,1,1,0,0),
    "NOT_RUN" -> Counters.build(1,0,0,0,1),
    "PARSE_FAILURE" -> Counters.build(1,0,0,1,0),
    "FAILED" -> Counters.build(1,1,0,1,0),
    "CHILD_FAILED" -> Counters.build(1,1,0,1,0))


  var counter : Long = 1L

  def uniqueId (realId : Long) = {
    val id = s"$counter-$realId"
    counter = counter + 1
    id
  }

}

case class FeatureDetails(summary : FeatureSummary, nodeDetails :  List[NodeDetail])

object FeatureDetails {
  def sequenceIds(featuresList: List[FeatureDetails], toAdd: Long): scala.List[_root_.org.substeps.report.FeatureDetails] = {

    featuresList.map(f => {
      f.copy(summary = FeatureSummary.sequenceIds(f.summary, toAdd), nodeDetails = NodeDetail.sequenceIds(f.nodeDetails, toAdd))
    })
  }
}

case class SourceDataModel(rootNodeSummary : RootNodeSummary, featuresList : List[FeatureDetails], config : Config)

case object SourceDataModel{

  def sequenceIds(srcDataList: List[SourceDataModel]) : List[SourceDataModel]  = {



    srcDataList match {
      case sdm :: Nil => srcDataList
      case head :: tail => {

        var toAdd = head.rootNodeSummary.id
        // renumber the tail list
        val newTail =
          tail.map(sd => {
            val copied =  sd.copy(rootNodeSummary = RootNodeSummary.sequenceIds(sd.rootNodeSummary, toAdd) , featuresList = FeatureDetails.sequenceIds(sd.featuresList, toAdd))
            toAdd = copied.rootNodeSummary.id
            copied
          })
        head :: newTail
      }
    }
  }


}

case class UncalledStepDef(line: String, source: String, lineNumber: Int)
case class UncalledStepImpl(value:String, implementedIn: String, keyword: String, method:String)


/**
  * Created by ian on 30/06/16.
  */
class ReportBuilder extends IReportBuilder with IndexPageTemplate with UsageTreeTemplate with GlossaryTemplate {

  // TODO need to leave here as legacy to ensure maven is able to inject in parameter, mojo fails otherwise
  @BeanProperty
  var reportDir : File = new File(".")

  private val log: Logger = LoggerFactory.getLogger("org.substeps.report.ReportBuilder")


  def safeCopy(src : File, dest : File): Unit = {
    try {
      FileUtils.copyFile(src, dest)
    }
    catch {
      case e : FileNotFoundException => log.warn("failed to find source file: " + src.getAbsolutePath)
    }
  }

  def buildGlossaryData(sourceJsonFile : File): List[GlossaryElement] = {
    implicit val formats = Serialization.formats(NoTypeHints)

    val glossaryElements =

      if (sourceJsonFile.exists()) {
        val data = read[List[StepImplDesc]](sourceJsonFile)

        data.map(sid => sid.expressions.map(sd => {

          val escapedExpression =
            sd.expression.replaceAll("\\$$", "").replaceAll("<", "&lt;").replaceAll(">", "&gt;")

          GlossaryElement(sd.section, escapedExpression, sid.className, sd.regex, sd.example, sd.description, sd.parameterNames, sd.parameterClassNames)
        })).flatten
      }
      else {
        List()
      }
    glossaryElements
  }

  override def buildFromDirectory(sourceDataDir: File, reportDir : File): Unit = {
    buildFromDirectory(sourceDataDir, reportDir, null)
  }


  import org.json4s._
  import org.json4s.native.JsonMethods._


  private def processUncalledAndUnusedDataFiles(reportRootDataDir : File, executionConfigs : List[Config]) (implicit reportDir : File) = {

    log.debug("processUncalledAndUnusedDataFiles")

    implicit val formats = DefaultFormats

    val uncalledStepDefs  =
    executionConfigs.flatMap(cfg =>{

      val dataDir = new File(reportRootDataDir, NewSubstepsExecutionConfig.getDataSubdir(cfg))

      val dataFile = new File(dataDir, "uncalled.stepdefs.js")

      if (dataFile.exists()) {

        val rawUncalledStepDefs = Files.asCharSource(dataFile, Charset.forName("UTF-8")).read()

        parse(rawUncalledStepDefs).extract[List[UncalledStepDef]]
      }
      else List()
    }).distinct


    val uncalledStepImpls =
    executionConfigs.flatMap(cfg =>{

      val dataDir = new File(reportRootDataDir, NewSubstepsExecutionConfig.getDataSubdir(cfg))

      val dataFile = new File(dataDir, "uncalled.stepimpls.js")
      if (dataFile.exists()) {
        val rawUncalledStepImpls = Files.asCharSource(dataFile, Charset.forName("UTF-8")).read()

        parse(rawUncalledStepImpls).extract[List[UncalledStepImpl]]
      }
      else
        List()
    }).distinct


    Files.write("var uncalledStepDefs=" + write(uncalledStepDefs), new File(reportDir, "uncalled.stepdefs.js"), Charset.forName("UTF-8"))

    Files.write("var uncalledStepImplementations=" + write(uncalledStepImpls), new File(reportDir, "uncalled.stepimpls.js"), Charset.forName("UTF-8"))
//    safeCopy(new File(sourceDataDir, "uncalled.stepdefs.js"), )
//    safeCopy(new File(sourceDataDir, "uncalled.stepimpls.js"), new File(reportDir, "uncalled.stepimpls.js"))

  }

  override def buildFromDirectory(sourceDataDir: File, repDir : File, stepImplsJson : File): Unit = {

    val created = repDir.mkdir()

    log.info("creating report in " + repDir.getAbsolutePath + " create returns: " + created)

    implicit val rDir : File = repDir

    val dataDir = new File(repDir, "data")
    if (!dataDir.mkdir()){
      log.error("failed to create dir: " + dataDir.getAbsolutePath)
    }

    FileUtils.copyDirectory(new File(sourceDataDir.getPath), dataDir)


    val cfgFile = new File(dataDir, "masterConfig.conf")

    val masterConfig = ConfigFactory.parseFile(cfgFile)

    import scala.collection.JavaConverters._

    val executionConfigs = SubstepsConfigLoader.splitMasterConfig(masterConfig).asScala.toList

    processUncalledAndUnusedDataFiles( dataDir, executionConfigs)

    val srcDataList: List[SourceDataModel] = SourceDataModel.sequenceIds(readModels(dataDir, executionConfigs))

    val detailData = createFile( "detail_data.js")

    createDetailData(detailData,srcDataList)

    val resultsTreeJs = createFile( "substeps-results-tree.js")

    val suiteDescription = masterConfig.getString("org.substeps.config.description")

    createTreeData2(resultsTreeJs,srcDataList, suiteDescription)

    val reportFrameHTMLFile = createFile( "index.html")


    val stats : ExecutionStats = buildExecutionStats(srcDataList)

    // only taking the head element for the timestamp
    val localDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(srcDataList.head.rootNodeSummary.timestamp), ZoneId.systemDefault())
    val dateTimeString = localDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"))


    // TODO - only taking the head element

    val rootNodeSummaries = srcDataList.map(_.rootNodeSummary)

    val reportFrameHtml = buildReportFrame(masterConfig, rootNodeSummaries, stats, dateTimeString)


    withWriter(reportFrameHTMLFile, writer => writer.append(reportFrameHtml))

    copyStaticResources()

    val statsByTag = buildExecutionStatsByTag(srcDataList)

    val statsJsFile = createFile("substeps-stats-by-tag.js")
    writeStatsJs(statsJsFile, statsByTag)


    val usageTreeDataFile = createFile("substeps-usage-tree.js")

    createUsageTree(usageTreeDataFile, srcDataList)

    val usageTreeHTMLFile  = createFile( "usage-tree.html")

    val usageTreeHtml = buildUsageTree()

    withWriter(usageTreeHTMLFile, writer => writer.append(usageTreeHtml) )

    createGlossary(stepImplsJson, dateTimeString)
  }

  def createGlossary(stepImplsJson : File, dateTimeString :String)(implicit reportDir : File): Unit = {

    if (Option(stepImplsJson).isDefined) {
      val glossaryHTML = createFile("glossary.html")
      val glossaryContent = buildGlossaryReport(dateTimeString)

      val glossaryData = buildGlossaryData(stepImplsJson)
      val glossaryJsFile = createFile("glossary-data.js")
      writeGlossaryJs(glossaryJsFile, glossaryData)

      withWriter(glossaryHTML, writer => writer.append(glossaryContent))
    }
  }

  def writeGlossaryJs(glossaryJSFile : File, glossaryData : List[GlossaryElement]): Unit = {

    withWriter(glossaryJSFile, writer => {
      implicit val formats = Serialization.formats(NoTypeHints)
      writer.append("var glossary=")
      writer.append(writePretty(glossaryData))
      writer.append(";\n")
    })
  }

  def writeStatsJs(statsJsFile: File, stats: (List[Counters], List[Counters])): Unit = {

    withWriter(statsJsFile, writer => {

      implicit val formats = Serialization.formats(NoTypeHints)

      writer.append("var featureStatsData = ")
      writer.append(writePretty(stats._1))
      writer.append(";\nvar scenarioStatsData = ")
      writer.append(writePretty(stats._2))
      writer.append(";\n")
    })
  }

  def buildExecutionStats(srcDataList: List[SourceDataModel]): ExecutionStats = {

    val combinedFeatureList = srcDataList.flatMap(s => s.featuresList)

    val featureAndScenarioCounters =
      combinedFeatureList.map(f => {

        val scenarioCounters =
          f.nodeDetails.map(scenario => {
            ReportBuilder.resultToCounters.get(scenario.result) match {

              case Some(counter) => counter
              case _ => throw new IllegalStateException("unhandled result type in stats counter: " + scenario.result)
            }
          })

        val fCounters =
        ReportBuilder.resultToCounters.get(f.summary.result) match {

          case Some(counter) => counter
          case _ => throw new IllegalStateException("unhandled result type in stats counter: " + f.summary.result)
        }

        (fCounters, scenarioCounters)
      })

    var featureCounter = Counters.ZERO
    featureAndScenarioCounters.foreach(fc => {
        featureCounter = featureCounter + fc._1
      } )


    val scenarioCounters =
    featureAndScenarioCounters.flatMap(fands => fands._2)

    var scenarioCounter = Counters.ZERO
    scenarioCounters.foreach(sc => {
      scenarioCounter = scenarioCounter + sc
    } )


    val allNodeDetails =
      combinedFeatureList.flatMap(f => {
      val scenarios = f.nodeDetails

      scenarios.flatMap(scenario => scenario.flattenTree())
    })

    val stepImplNodeDetails = allNodeDetails.filter(nd => nd.nodeType == "Step")
    val substepNodeDetails = allNodeDetails.filter(nd => nd.nodeType == "SubstepNode")

    val substepCounters =
      substepNodeDetails.foldLeft(Counters.ZERO) { (counters, nodeDetail) =>

        ReportBuilder.resultToCounters.get(nodeDetail.result) match {
          case Some(c) => counters + c
          case None => counters
        }
      }

    val stepImplCounters =
      stepImplNodeDetails.foldLeft(Counters.ZERO) { (counters, nodeDetail) =>

        ReportBuilder.resultToCounters.get(nodeDetail.result) match {
          case Some(c) => counters + c
          case None => counters
        }
      }

    ExecutionStats(featureCounter, scenarioCounter, substepCounters, stepImplCounters)

  }


  def buildExecutionStatsByTag(srcDataList: List[SourceDataModel]): (List[Counters], List[Counters]) = {

    val allNodeDetails =
      srcDataList.flatMap(srcData => {
        srcData.featuresList.flatMap(f => {
          val scenarios = f.nodeDetails
          scenarios.flatMap(scenario => scenario.flattenTree())
        })
      })


    val scenarioNodeDetails = allNodeDetails.filter(nd => nd.nodeType == "BasicScenarioNode")

    def getCountersForTags(nodeDetails : List[NodeDetail]) = {
      val tags = nodeDetails.flatMap(n => n.tags).flatten.distinct

      tags.map(t => {
        val counters = nodeDetails.filter(n => n.tags.isDefined && n.tags.get.contains(t)).flatMap(n => ReportBuilder.resultToCounters.get(n.result))

        (counters.foldLeft(Counters.ZERO) { (a, b) => a + b }).copy(tag = Some(t))

      })
    }

    val untaggedCounters = scenarioNodeDetails.filter(n => n.tags.isEmpty).flatMap(n => ReportBuilder.resultToCounters.get(n.result))
    val totalUntaggedScenarios = (untaggedCounters.foldLeft(Counters.ZERO) { (a, b) => a + b }).copy(tag = Some("un-tagged"))

    val scenarioCountersWithTags = getCountersForTags(scenarioNodeDetails)



    val featureSummaries = //srcData.features.map(_.summary)
      srcDataList.flatMap(srcData => {
        srcData.featuresList.map(_.summary)})

    val allFeatureTags = featureSummaries.flatMap(f => f.tags).distinct

    val featureCountersWithTags =
    allFeatureTags.map(t => {
      val counters = featureSummaries.filter(f => f.tags.contains(t)).flatMap(n => ReportBuilder.resultToCounters.get(n.result))
      (counters.foldLeft(Counters.ZERO) { (a, b) => a + b }).copy(tag = Some(t))
    })

    val untaggedFeatureSummaries = featureSummaries.filter(n => n.tags.isEmpty).flatMap(n => ReportBuilder.resultToCounters.get(n.result))
    val totalUntaggedFeatureSummaries = (untaggedFeatureSummaries.foldLeft(Counters.ZERO) { (a, b) => a + b }).copy(tag = Some("un-tagged"))


    val result = (featureCountersWithTags :+ totalUntaggedFeatureSummaries, scenarioCountersWithTags :+ totalUntaggedScenarios)
    result
  }

  def getHierarchy(nodeDetail : NodeDetail, allNodes : List[NodeDetail]) :  List[NodeDetail] = {

    allNodes.find(n => n.children.exists(c => c.id == nodeDetail.id)) match {

      case None => List(nodeDetail)
      case Some(parent) => List(nodeDetail) ++ getHierarchy(parent, allNodes)

    }
  }

  def getCallHierarchy(thisNodeDetail : NodeDetail, allNodes : List[NodeDetail]) : List[NodeDetail] = {

    allNodes.find(n => n.children.exists(c => c.id == thisNodeDetail.id)) match {
      case None => List() // this node is not a child of any other node
      case Some(parent) => parent +: getCallHierarchy(parent, allNodes) // prepend
    }

  }


  def createUsageTree(usageTreeDataFile: File, srcDataList: List[SourceDataModel]): Unit = {

    val allNodeDetails =
      srcDataList.flatMap(srcData => {
        srcData.featuresList.flatMap(f => {
          val scenarios = f.nodeDetails
          scenarios.flatMap(scenario => scenario.flattenTree())
        })
      })


    val methodNodes = getJSTreeCallHierarchyForStepImpls( allNodeDetails)

    val substepDefNodes = getJSTreeCallHierarchyForSubstepDefs( allNodeDetails)


    withWriter(usageTreeDataFile, writer => {
      writer.append("var stepImplUsageTreeData=")

      implicit val formats = Serialization.formats(NoTypeHints)

      writer.append(writePretty(methodNodes))
      writer.append(";\nvar substepDefUsageTreeData=")

      writer.append(writePretty(substepDefNodes))

      writer.append(";")

    })
  }

  def getJSTreeCallHierarchyForSubstepDefs(allNodeDetails : List[NodeDetail]): Iterable[JsTreeNode] = {

    val substepNodeDetails = allNodeDetails.filter(nd => nd.nodeType == "SubstepNode")
    val substepDefsByUniqueMethood = substepNodeDetails.groupBy(_.source.get)

    var nextId = allNodeDetails.map(n => n.id) match {
      case Nil => 1
      case ids => ids.max + 1
    }


    substepDefsByUniqueMethood.map(e => {

      val substepDef = e._1

      // child nodes for each usage
      val substepJsTreeNodes =

      e._2.map(substep => {

        val callHierarchy = getCallHierarchy(substep, allNodeDetails)

        var lastChildOption: Option[List[JsTreeNode]] = None

        callHierarchy.reverse.foreach(n => {

          val last = JsTreeNode(ReportBuilder.uniqueId(n.id), n.description, n.nodeType + " " + n.result, lastChildOption, State(false)) // might want to have more info - failures for example

          lastChildOption = Some(List(last))

        })
        lastChildOption.get.head.copy(li_attr = Some(Map("data-substep-def-call" -> substep.description)))
      })


      // calculate the pass / fail / not run %

      val total = e._2.size

      val failures = e._2.count(n => n.result =="FAILED" || n.result =="CHILD_FAILED")

      val passes = e._2.count(n => n.result =="PASSED")

      val notRun = e._2.count(n => n.result =="NOT_RUN")

      val failPC = Counters.pc(failures, total)
      val passPC = Counters.pc(passes, total)
      val notRunPC = Counters.pc(notRun, total)


      JsTreeNode(ReportBuilder.uniqueId(nextId), StringEscapeUtils.ESCAPE_HTML4.translate(substepDef), "SubstepDefinition", Some(substepJsTreeNodes), State(true),
        Some(Map("data-substep-def" -> "true", "data-substepdef-passpc" -> s"${passPC}",
        "data-substepdef-failpc" -> s"${failPC}", "data-substepdef-notrunpc" -> s"${notRunPC}")))
    })
  }


  def getJSTreeCallHierarchyForStepImpls(allNodeDetails : List[NodeDetail]): Iterable[JsTreeNode] = {

    val stepImplNodeDetails = allNodeDetails.filter(nd => nd.nodeType == "Step")

    val stepImplsbyUniqueMethood = stepImplNodeDetails.groupBy(_.method.get)


    var nextId = allNodeDetails.map(n => n.id) match {
      case Nil => 1
      case ids => ids.max + 1
    }


    val stepImplNodeDetailsToUsages =
    stepImplsbyUniqueMethood.toList.map(e => {
      // convert the method reference to an examplar instance of such a node detail

      (allNodeDetails.filter(n => n.method.contains(e._1)),  e._2)

    }).sortBy(_._1.head.source.get)



    stepImplNodeDetailsToUsages.map(e => {

      val exemplarNodeDetail = e._1.head

      val nodeIds =
        e._1.map(n => {
          n.id
        })

      // child nodes for each usage
      val stepImplJsTreeNodes =

      e._2.map(stepImpl => {

        val callHierarchy = getCallHierarchy(stepImpl, allNodeDetails)

        var lastChildOption: Option[List[JsTreeNode]] = None

        callHierarchy.reverse.foreach(n => {

          val last = JsTreeNode(ReportBuilder.uniqueId(n.id), n.description, n.nodeType + " " + n.result, lastChildOption, State(false)) // might want to have more info - failures for example

          lastChildOption = Some(List(last))

        })
        lastChildOption.get.head
      })

      // calculate the pass / fail / not run %

      val total = e._2.size

      val failures = e._2.count(n => n.result =="FAILED" || n.result =="CHILD_FAILED")

      val passes = e._2.count(n => n.result =="PASSED")

      val notRun = e._2.count(n => n.result =="NOT_RUN")

      val failPC = Counters.pc(failures, total)
      val passPC = Counters.pc(passes, total)
      val notRunPC = Counters.pc(notRun, total)


      // this jstree node represents the method
      val jstreeNode = JsTreeNode(ReportBuilder.uniqueId(nextId),
        StringEscapeUtils.ESCAPE_HTML4.translate(exemplarNodeDetail.source.get), "method", Some(stepImplJsTreeNodes), State(true),
        Some(Map("data-stepimpl-method" -> s"${exemplarNodeDetail.method.get}", "data-stepimpl-passpc" -> s"${passPC}",
          "data-stepimpl-failpc" -> s"${failPC}", "data-stepimpl-notrunpc" -> s"${notRunPC}", "data-stepimpl-node-ids" -> nodeIds.mkString(","))))

      nextId = nextId + 1

      jstreeNode

    })
  }


  @throws[URISyntaxException]
  @throws[IOException]
  def copyStaticResources()(implicit repDir : File): Unit = {

    this.log.debug("Copying old_static resources to: " + repDir.getAbsolutePath)
    val staticURL = getClass.getResource("/static")
    if (staticURL == null) throw new IllegalStateException("Failed to copy old_static resources for report.  URL for resources is null.")
    copyResourcesRecursively(staticURL, repDir)
  }


  @throws[IOException]
  def copyResourcesRecursively(originUrl: URL, destination: File): Unit = {
    val urlConnection = originUrl.openConnection
    if (urlConnection.isInstanceOf[JarURLConnection]) copyJarResourcesRecursively(destination, urlConnection.asInstanceOf[JarURLConnection])
    else if (originUrl.getProtocol.toLowerCase.startsWith("file")) FileUtils.copyDirectory(new File(originUrl.getPath), destination)
    else throw new SubstepsException("URLConnection[" + urlConnection.getClass.getSimpleName + "] is not a recognized/implemented connection type.")
  }

  @throws[IOException]
  def copyJarResourcesRecursively(destination: File, jarConnection: JarURLConnection): Unit = {
    val jarFile = jarConnection.getJarFile
    import scala.collection.JavaConversions._
    for (entry <- Collections.list(jarFile.entries)) {
      if (entry.getName.startsWith(jarConnection.getEntryName)) {
        val fileName = StringUtils.removeStart(entry.getName, jarConnection.getEntryName)
        if (!entry.isDirectory) {

          val entryInputStream = jarFile.getInputStream(entry)
          try {
            val byteSink = Files.asByteSink(new File(destination, fileName), FileWriteMode.APPEND)
            byteSink.writeFrom(entryInputStream)
            // FileUtils.copyInputStreamToFile(entryInputStream, new File(destination, fileName))
          }
          finally {

            IOUtils.closeQuietly(entryInputStream)

          }
        }
        else new File(destination, fileName).mkdirs
      }
    }
  }


  def createFile(name : String)(implicit reportDir : File): File = {
    val f = new File(reportDir, name)
    if (!f.createNewFile()){
      log.error("failed to create file: " + f.getAbsolutePath)
    }
    f
  }

  def writeResultSummaryX(resultsFile: RootNodeSummary)(implicit reportDir : File): Unit = {

    val file = createFile("results-summary.js")

    withWriter(file, writer => {
      writer.append("var resultsSummary=")

      val map = Map("description" -> resultsFile.description,
      "timestamp" -> resultsFile.timestamp,
      "result" -> resultsFile.result,
      "environment" -> resultsFile.environment,
      "nonFatalTags" -> resultsFile.nonFatalTags,
        "tags" -> resultsFile.tags)

      implicit val formats = Serialization.formats(NoTypeHints)

      writer.append(writePretty(map)).append(";")

    })

  }

  def readModels(reportRootDataDir : File, executionConfigs : List[Config])(implicit reportDir : File) : List[SourceDataModel] = {

    // each exec config will have an output dir
    executionConfigs.map(cfg =>{

      NewSubstepsExecutionConfig.setThreadLocalConfig(cfg)

      val dataDir = new File(reportRootDataDir, NewSubstepsExecutionConfig.getDataSubdir(cfg))

      readModel(dataDir, cfg)
    })


  }

  def readModel(srcDir : File, config : Config)(implicit reportDir : File) : SourceDataModel = {

    implicit val formats = Serialization.formats(NoTypeHints)

    val resultsFileOption: Option[RootNodeSummary] = {

      val files = srcDir.listFiles().toList
      files.find(f => f.getName == "results.json").map(resultsFile => {

        read[RootNodeSummary](Files.asCharSource(resultsFile, Charset.defaultCharset()).read())
      })
    }
    val featureSummaries =

      resultsFileOption match {
      case Some(resultsFile) => {

        //writeResultSummary(resultsFile)

        resultsFile.features.flatMap(featureSummary => {

          val featureFileResultsDir = new File(srcDir, featureSummary.resultsDir)
          loadFeatureData(featureFileResultsDir) match {

            case Some (f) => {

              val scenarioDetails =
              f.scenarios.map(scenarioSummary => {

                val scenarioResultsFile = new File(featureFileResultsDir, scenarioSummary.filename)


                read[NodeDetail](Files.asCharSource(scenarioResultsFile, Charset.defaultCharset()).read())

              })

              Some(f, scenarioDetails)

            }
            case None => None
          }
        })
      }
      case None => List()
    }

    val featureDetailList =
      featureSummaries.map(fs => FeatureDetails(fs._1, fs._2))

    new SourceDataModel(resultsFileOption.get, featureDetailList, config)

  }

  def loadFeatureData(srcDir : File): Option[FeatureSummary] = {

    implicit val formats = Serialization.formats(NoTypeHints)

    srcDir.listFiles().toList.find(f => f.getName == srcDir.getName + ".json").map(featureResultsFile =>{

      read[FeatureSummary](Files.asCharSource(featureResultsFile, Charset.defaultCharset()).read())
    })

  }

  def createTreeData3(srcData : SourceDataModel): JsTreeNode = {

    val children =
      srcData.featuresList.map(features => {

        val featureScenarios = features.nodeDetails.map(n => buildForJsonTreeNode(n))

        val childrenOption = if (featureScenarios.isEmpty) None else Some(featureScenarios)

        // create a node for the feature:
        val icon = ReportBuilder.iconFor(features.summary.result)

        val state = State.forResult(features.summary.result)

        JsTreeNode(features.summary.id.toString, features.summary.description, icon, childrenOption, state)

      })

    val childrenOption = if (children.isEmpty) None else Some(children)

    val icon = ReportBuilder.iconFor(srcData.rootNodeSummary.result)

    val state = State.forResult(srcData.rootNodeSummary.result)

    JsTreeNode(srcData.rootNodeSummary.id.toString, srcData.rootNodeSummary.description, icon, childrenOption, state)

  }

  def consolidateResult(results : List[String]) : String = {

    if (results.contains("FAILED")) "FAILED"
    else if (results.contains("CHILD_FAILED")) "CHILD_FAILED"
    else if (results.contains("PARSE_FAILURE")) "PARSE_FAILURE"
    else "PASSED"
  }


  def createTreeData2(file : File, srcDataList : List[SourceDataModel], rootDescription : String): Unit = {

    val srcDataJsTreeNodes =
      srcDataList.map(srcData => createTreeData3(srcData))

    val ooberRootNode : JsTreeNode =
      if (srcDataJsTreeNodes.size > 1){

        val combinedResult = consolidateResult(srcDataList.map(s => s.rootNodeSummary.result))

        val icon = ReportBuilder.iconFor(combinedResult)

        val state = State.forResult(combinedResult)

        JsTreeNode("-1", rootDescription, icon, Some(srcDataJsTreeNodes), state)

      }
      else {
        srcDataJsTreeNodes.head
      }

    withWriter(file, writer =>{
      writer.append("var treeData = ")

      implicit val formats = Serialization.formats(NoTypeHints)

      writer.append(writePretty(ooberRootNode))
    })

  }


  def withWriter(file: File, op: BufferedWriter => Any): Unit = {
    val writer = Files.newWriter(file, Charset.defaultCharset)
    op(writer)
    writer.flush()
    writer.close()
  }


  def buildForNode(node : NodeDetail) : Map[String, Object] = {

    val children =
    node.children.map(child => {
      buildForNode(child)
    })

    val childrenMap = if (children.isEmpty) Map() else Map("children" -> children)

    val icon = ReportBuilder.iconFor(node.result)

    val openStateMap = ReportBuilder.openStateFor(node.result)

    val dataMap = Map("title" -> node.description, "attr" -> Map("id" -> node.id.toString), "icon" -> icon) ++ openStateMap

    val result =   Map("data" -> dataMap) ++ childrenMap

    result
  }


  def buildForJsonTreeNode(node : NodeDetail) : JsTreeNode = {

    val children =
      node.children.map(child => {
        buildForJsonTreeNode(child)
      })


    val childrenOption = if (children.isEmpty) None else Some(children)

    val icon = ReportBuilder.iconFor(node.result)

    val state = State.forResult(node.result)

    JsTreeNode(node.id.toString, node.description, icon, childrenOption, state)

  }




  def createDetailData(file : File, srcDataList : List[SourceDataModel]): Unit = {

    withWriter(file, writer => {

      writer.append("var detail = new Array();\n")

      srcDataList.foreach(srcData => {

        val featureSummaries = srcData.featuresList.map(f => f.summary) //srcData._2.map(_._1)

        writeRootNode(writer, srcData.rootNodeSummary, featureSummaries, srcData.config)

        srcData.featuresList.foreach(feature =>{

          val nodeDetailList = feature.nodeDetails

          val dataSubdir = NewSubstepsExecutionConfig.getDataSubdir(srcData.config)

            writeFeatureNode(writer, feature.summary, nodeDetailList, dataSubdir)
        })

      })

    })
  }


  def writeFeatureNode(writer: BufferedWriter, f: FeatureSummary, nodeDetailList: List[NodeDetail], dataSubdir : String): Unit = {

    writer.append(s"""detail[${f.id}]={"nodetype":"FeatureNode","filename":"${f.filename}","result":"${f.result}","id":${f.id},
       |"runningDurationMillis":${f.executionDurationMillis.getOrElse(-1)},"runningDurationString":"${f.executionDurationMillis.getOrElse(-1)} milliseconds",
       |"description":"${StringEscapeUtils.escapeEcmaScript(f.description)}",
       |"children":[""".stripMargin.replaceAll("\n", ""))



    val children =
    f.scenarios.map(sc => {

      val nodeDetailOption = nodeDetailList.find(n => n.id == sc.nodeId)
      if(nodeDetailOption.isEmpty){
        println("stop")
      }
      val nodeDetail = nodeDetailOption.get

      s"""{"result":"${sc.result}","description":"${nodeDetail.description}"}"""
    })

    writer.append(children.mkString(","))
    writer.append("]};\n")

    f.scenarios.foreach(sc => {

      val scenarioNodeDetail = nodeDetailList.find(n => n.id == sc.nodeId).get

      writeNodeDetail(writer, scenarioNodeDetail, dataSubdir)
    })
  }

  def writeNodeDetail(writer: BufferedWriter, nodeDetail: NodeDetail, dataSubdir : String) : Unit = {

    writer.append(s"""detail[${nodeDetail.id}]={"nodetype":"${nodeDetail.nodeType}","filename":"${nodeDetail.filename}","result":"${nodeDetail.result}","id":${nodeDetail.id},
    |"runningDurationMillis":${nodeDetail.executionDurationMillis.getOrElse(-1)},
    |"runningDurationString":"${nodeDetail.executionDurationMillis.getOrElse("n/a")} milliseconds","description":"${StringEscapeUtils.escapeEcmaScript(nodeDetail.description)}",""".stripMargin.replaceAll("\n", ""))

    nodeDetail.method.map(s => writer.append(s""""method":"${StringEscapeUtils.escapeEcmaScript(s)}",""") )

    writer.append(s""""lineNum":"${nodeDetail.lineNumber}",""")

    nodeDetail.exceptionMessage.map(s => writer.append(s""""emessage":"${StringEscapeUtils.escapeEcmaScript(s)}",""") )

    nodeDetail.screenshot.map(s => {

      writer.append(s"""screenshot:"data/${dataSubdir}${s}",""")
    } )
    nodeDetail.stackTrace.map(s => writer.append(s"""stacktrace:[${s.mkString("\"", "\",\n\"", "\"")}],"""))

    writer.append(s""""children":[""")

    writer.append(nodeDetail.children.map(n => {
      s"""{"result":"${n.result}","description":"${StringEscapeUtils.escapeEcmaScript(n.description)}"}"""
    }).mkString(","))

    writer.append("]};\n")

    nodeDetail.children.foreach(child => {
      writeNodeDetail(writer, child, dataSubdir)
    })
  }



  def writeRootNode(writer : BufferedWriter, rootNodeSummary : RootNodeSummary, featureSummaries : List[FeatureSummary], config : Config): io.Writer = {

    val descriptionProvider : RootNodeDescriptionProvider = NewSubstepsExecutionConfig.getRootNodeDescriptor(config)

    val rootNodeDescription = descriptionProvider.describe(config)

    writer.append(s"""detail[${rootNodeSummary.id}]={"nodetype":"RootNode","description": "${rootNodeDescription}", "filename":"","result":"${rootNodeSummary.result}","id":${rootNodeSummary.id},"runningDurationMillis":${rootNodeSummary.executionDurationMillis.get},"runningDurationString":"${rootNodeSummary.executionDurationMillis.get} milliseconds","children":[""")

      writer.append(
        rootNodeSummary.features.map(f => {

          val fs = featureSummaries.find(fs => fs.id == f.nodeId).get

          s"""{"result":"${f.result}","description":"${StringEscapeUtils.escapeEcmaScript(fs.description)}"}"""
        }).mkString(","))

      writer.append("]};\n")

  }
}

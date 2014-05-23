
package buffercards

import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import scala.collection.JavaConverters._
import com.amazonaws.services.sqs.model.SendMessageRequest
import com.json.generators.JsonGeneratorFactory
import com.json.parsers.JsonParserFactory
import com.typesafe.config._
import org.apache.log4j.Logger
import scala.collection.mutable.LinkedHashMap
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import java.io._

/**
 * Base Twitter Card Type trait
 */
sealed trait BaseCardType

/**
 * Specific card types
 */
case object AppCard extends BaseCardType
case object SummaryCard extends BaseCardType
case object PlayerCard extends BaseCardType

/**
 * Generic type of Message with the base attributes
 * Constructor
 * @param cardType the type of card inheriting from BaseCardType
 * @param title the title of the card - see Twitter Doc
 * @param desc the description of the card - see Twitter Doc
 */
abstract class BaseTwitterCard(cardType : BaseCardType,
                  title : String, desc : String){
  /**
   * Get the Meta Tags
   */
  def toMetaTags() : String 
}

/**
 * Twitter App Card Type
 * @see https://dev.twitter.com/docs/cards/types/app-card
 */
case class AppCard(title : String, desc : String, iphoneId : String,
                   ipadId : String, androidPkg : String) 
  extends BaseTwitterCard(AppCard, title, desc){
    /**
     * Produce the meta tags
     */
    def toMetaTags() : String  = {
      // use the Elem to build the xml
      val metas = <metas>
        <meta name="twitter:card" content="app"/>
        <meta name="twitter:description" content="%s"/>
        <meta name="twitter:title" content="%s"/>
        <meta name="twitter:app:id:iphone" content="%s"/>
        <meta name="twitter:app:id:ipad" content="%s"/>
        <meta name="twitter:app:id:googleplay" content="%s"/>
        <meta name="twitter:app:country" content="us"/>
      </metas>

      // format the string
      val xmlStr = metas.toString().format(desc, 
                                           title, iphoneId, ipadId, androidPkg)
      
      // remove the / and meta tags
      xmlStr.replace("/","").toString().replace("metas","").toString().replace("<>","")
    }  
}

/**
 * Response of creating a card
 */
sealed trait CardResponse
case class CardResponseOK(fileName : String, id : String) extends CardResponse
case class CardResponseFail(msg : String, ex : Throwable) extends CardResponse

object Main {
  // the logger
  val log = Logger.getLogger("CardProcessor")
  
  // config - loaded from classpath
  val conf = ConfigFactory.load()
  
  
  // In & Out Queue URLs
  val inQUrl = conf.getString("buffer-app.sqs.inQueueUrl")
  val outQUrl = conf.getString("buffer-app.sqs.outQueueUrl")
    
  
  /**
   * Wrapper over _processCardRequest see below
   */
  def processCardRequest(jsonStr : String) = {
    // try process
    val tryReq = Try(_processCardRequest(jsonStr))
    
    // deal with response
    tryReq match{
      case Success(either) => {
          // we insert msg to outQ
          val inJsonMap = new java.util.HashMap[String,String]    
          inJsonMap.put("cmd","create-resp")
          
          // deal with response from method
          either.isLeft match {
            case true => {
                // tell Front End it worked
                inJsonMap.put("status","ok")
                inJsonMap.put("id",either.left.get.id)
                inJsonMap.put("path",either.left.get.fileName)                
            }
            case false => {
                // tell Front End it worked
                inJsonMap.put("status","fail")
                inJsonMap.put("reason",either.right.get.msg)
                inJsonMap.put("exception",either.right.get.ex.toString) 
            }
          }
         
          // debug
          log.debug(s"Sending back > ${inJsonMap}")
          val gFactory = JsonGeneratorFactory.getInstance();
          val gGenerator = gFactory.newJsonGenerator();
          val inJsonStr = gGenerator.generateJson(inJsonMap);
          log.info(s"Sending Out JSON -> ${inJsonStr}")
          val isqs = new AmazonSQSClient()
          
          // send the msg
          isqs.sendMessage(new SendMessageRequest(inQUrl, inJsonStr));
          log.info("Done!")
      }
      case Failure(ex) => {
          log.fatal(s"Could not process req -> ${ex}")
      }      
    }
  }
  
  /**
   * Accepts a JSON String from the inQ to process 
   * Three main types of JSON msgs (Create, Update, Destroy)
   * -Create will create a new card and create a static html file with the metas
   * -Update will update an existing card's static html
   * -Destroy will remove the existing card's static html
   * 
   * For this demo we are only working with Create, for the Card Type
   * It ought to have elements of sufficient to instantiate the AppCard type
   * 
   * This method will so create the static html and then insert a response to
   * the outQ with the newly created url or failure msg if any
   * 
   * It should be ran Asynchronously for good performance - but for this
   * example it is not
   * 
   * @param jsonStr see above 
   * @return either the CardResponseOK or CardResponseFail instance
   */
  def _processCardRequest(jsonStr : String) : Either[CardResponseOK, CardResponseFail] = {
    // cast it to a JSON Obj
    log.info("Casting to JSON")
    // remove the leading and trailing ] - this is hack
    val jsonStrItem = jsonStr.replace("]","").toString().replace("[","")
    val factory = JsonParserFactory.getInstance()
    val parser = factory.newJsonParser()
    // mutable map
    val _jsonData = parser.parseJson(jsonStrItem).asScala
    
    // immutable map
    val jsonData = collection.immutable.Map(_jsonData.toList: _*)
    
    // debug the elements
    jsonData.map(k => {log.debug(k)})
    
    // now extract what we need
    log.info(jsonData)
    
    // get the command
    jsonData.get("cmd") match{
      // no cmd?
      case None => {
          log.fatal("Command missing from Json - cannot process")
          Right(CardResponseFail("Command missing from json", 
                                 new IllegalArgumentException()))
      }
      // some cmd?
      case Some(cmd) => { 
          // now process by type
          cmd toString match{
            case "create" => {
                // create instance of the CardApp
                val cardAppTry : Try[AppCard] = Try(new AppCard(
                    jsonData.get("title").get toString,
                    jsonData.get("desc").get toString,
                    jsonData.get("iphoneId").get toString,
                    jsonData.get("ipadId").get toString,
                    jsonData.get("pkgName").get toString))
                
                // handle gracefully
                cardAppTry match{
                  case Success(cardApp) => {
                      // now create get the Metas
                      val metas = cardApp.toMetaTags()
                      
                      // create their meta file
                      val html = s"<head><title>${cardApp.title}</title>${metas}</head>"
                      log.debug(html)
                      
                      // get the dest dir & write the file
                      val dest =  System.getProperty("buffer.destdir").
                                  concat(System.getProperty("file.separator")).
                                  concat(cardApp.title.toLowerCase().replace(" ","_"))
                                  .concat(".html")
                                  
                      // wirte the file
                      log.info(s"Writing to ${dest}")
                      printToFile(new File(dest))(p => {
                              p.println(html)
                            })   
                      
                      // done!
                      log.info("File created")
                      Left(CardResponseOK(dest, "some-id"))
                      
                  }
                  case Failure(ex) => {
                      log.fatal(s"Missing fields in json for TwitterCardApp -> ${ex}")
                      Right(CardResponseFail("missing fields in json", ex))
                  }
                }
            }
            case _ => {
                log.fatal(s"${cmd} is an invalid command name!") 
                Right(CardResponseFail(s"Invalid command ${cmd}", 
                                       new IllegalArgumentException()))
            }              
          }
      }
    }   
  }
  
  /**
   * Print to file
   */
  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

  /**
   * @param args the command line arguments
   */
  def main(args: Array[String]): Unit = {
    
    //println(System.getProperty("buffer.destdir"))
   
    // uncomment this to insert some msgs to the inQ
//    val inJsonMap = new java.util.HashMap[String,String]    
//    inJsonMap.put("cmd","create")
//    inJsonMap.put("title","BufferApp")
//    inJsonMap.put("desc","Update Your Twitter, FB, Vine")
//    inJsonMap.put("iphoneId","306934135")
//    inJsonMap.put("ipadId","306934135")
//    inJsonMap.put("pkgName","co.vine.android")
//    val gFactory = JsonGeneratorFactory.getInstance();
//    val gGenerator = gFactory.newJsonGenerator();
//    val inJsonStr = gGenerator.generateJson(inJsonMap);
//    log.info(s"In JSON -> ${inJsonStr}")
//    val isqs = new AmazonSQSClient()
//    
//    // add 50 times
//    1 to 50 foreach {_ =>
//        isqs.sendMessage(new SendMessageRequest(inQUrl, inJsonStr));
//    }
    
    
    // subsribe to the SQS
    log.info("Starting!")
    val sqs = new AmazonSQSClient()
    log.info(s"Drainig the Queue at ${inQUrl} 10 at a time")
    val recv = new ReceiveMessageRequest(inQUrl);
    recv.setMaxNumberOfMessages(1)
    log.info(s"Picking the next batch")
    
    // accept messages and process them in a future loop
    val lst = sqs.receiveMessage(recv).getMessages().asScala;    
    lst.map{ msg => {
        println(msg.getBody)
        processCardRequest(msg getBody)
      }}  
  }
}
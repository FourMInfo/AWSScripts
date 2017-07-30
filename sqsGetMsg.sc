import $ivy.`io.circe::circe-core:0.8.0`
import $ivy.`io.circe::circe-generic:0.8.0`
import $ivy.`io.circe::circe-parser:0.8.0`
import $ivy.`io.circe::circe-optics:0.8.0`


import $plugin.$ivy.`org.scalamacros:::paradise:2.1.0`

import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._,io.circe.optics.JsonPath._

import scala.util.Try
import scala.util.{Success,Failure}

// import the Ciris configuration
import $exec.awsConfig
import awsConfig._

//set working directory
val mwd = pm

// case class for QueueURL
case class QueueURL(QueueUrl: String)
/* 
*** case classes for SQS messages - use optics instead to many problems with structure ***
* //case classes for S3 event
* case class UserIdentity(principalId: String)
* case class RequestParameters(sourceIPAddress: String)
* // these strings cause problems - likely because they dont match teh JSON which arent valid scala
* case class ResponseElements(xAmzRequestId: String, xAmzRequestId2: String)
* case class OwnerIdentity(principalId: String)
* case class Bucket(name: String, ownerIdentity: OwnerIdentity, arn: String)
* case class S3Object(key: String, size: String, eTag: String, sequencer: String)
* case class S3(s3SchemaVersion: String, configurationId: String, bucket: Bucket, s3Object: S3Object)
* case class S3Event(eventVersion: String, eventSource: String, awsRegion: String, 
*                     eventTime: String, eventName: String, userIdentity: UserIdentity,
*                     requestParameters: RequestParameters, responseElements: ResponseElements,
*                     s3: S3)
* case class S3Events(Records: List[S3Event])
*
*** case classes for Message Body not necessary - turns out Body is a string ***
* case class Records(records: List[S3Event]) 
* case class AttributesL(SenderId: String, ApproximateFirstReceiveTimestamp: String, 
*                         ApproximateReceiveCount: String, SentTimestamp: String)
* case class SQSMessage(MessageId: String: String, ReceiptHandle: String, MD5OfBody: String, Body: String, Buffer, Attributes: AttributesL)
* case class SQSMessages(Messages: List[SQSMessage])
*/
val nQueues = sqsQueues match {
    case Right(sqsQueues) => sqsQueues.numQueues
    case Left(sqsQueues) => 0
}

val listQueues = sqsQueues match {
    case Right(sqsQueues) => sqsQueues.sqsQueueList
    case Left(sqsQueues) => Nil
}

// iterate over the queues
for (sQueue <- sqsQueues) {
    //do a Try on the aws sqs call to getURL
    val qURLT = Try(%%('aws,"sqs","get-queue-url","--queue-name",sQueue))
    val qURLS = qURLT match {
        case Success(qURLT) => qURLT.out.string
        case Failure(qURLT) => qURLT.getMessage
    }
    if (qURLT.isSuccess) {
        // we assume since the Try succeeded the decode of the JSON will give correct value
        val qURL = parser.decode[QueueURL](qURLS).right.get.QueueUrl
        //do a Try on the aws sqs call to get the SQS message
        val eventT = Try(%%('aws,"sqs","receive-message","--queue-url",qURL,"--attribute-names","All","--message-attribute-names","All","--max-number-of-messages","1"))
        val eventS = eventT match {
            case Success(eventT) => eventT.out.string
            case Failure(eventT) => eventT.getMessage
        }
        // get the SQS Messages as Json
        val sqsMessageJ = parse(eventS).getOrElse(Json.Null)
        //example with index but not the right thing to do here
        //val _receiptHandle = root.Messages.index(0).ReceiptHandle.string
        //val receiptHandle: Option[String] = _receiptHandle.getOption(eventJ)
        // Create List of each Messages as separate Json then iterate over them
        val _sqsMessages = root.Messages.each.json   //Optics traversal definition
        val sqsMessages: List[Json] = _sqsMessages.getAll(sqsMessageJ)
        for (message <- sqsMessages) {
            // get the receiptHandle - wval e'll need it to delete the message later
            val _receiptHandle = root.ReceiptHandle.string   //Optics traversal definition
            val receiptHandle: Option[String] = _receiptHandle.getOption(message)
            // the body which contains the S3 event is a string. So have to Hack...
            val _s3Body = root.Body.string
            val s3BodySP = _s3Body.getOption(message).getOrElse("").toString
            // single quotes are malformed Json
            s3BodyR = s3BodySP.replace('\'','"')
            // without carriage returns its not a multi-line string & hence malformed
            val s3BodyN = s3BodyR.replace(",",",\n")
            // keyword object is ascala keyword and causes problems
            val s3BodyO = s3BodyN.replace("object","s3Object")
            val s3BodyJ = parse(s3BodyO).getOrElse(Json.Null)
            val _s3 = root.Records.index(0).s3.json
            val s3J = _s3.getOption(s3BodyJ).getOrElse(Json.Null)
            // We're finally ready to get the strings we need - bucket name and key!
            val _bucketName = root.bucket.name.string
            val bucketName: Option[String] = _bucketName.getOption(s3J)
            val _s3Key = root.s3Object.key.string
            al s3Key = _s3Key.getOption(s3J)
        }


    }
    } else {
        println(qURLS)
    }
}

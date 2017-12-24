package $package$

import java.util.UUID

import com.amazonaws.services.dynamodbv2.model.{AttributeValue, PutItemRequest, ReturnValue}
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBClientBuilder}
import com.amazonaws.services.lambda.runtime.Context
import com.github.dnvriend.lambda._
import com.github.dnvriend.lambda.annotation.{DynamoHandler, HttpHandler}
import play.api.libs.json.Reads._
import play.api.libs.json.{Json, _}

import scala.collection.JavaConverters._

object Person {
  implicit val format: Format[Person] = Json.format[Person]
}
final case class Person(name: String, id: Option[String] = None)

object PersonRepository {
  final val TableName = {
    val projectName = sys.env("PROJECT_NAME")
    val stage = sys.env("STAGE")

  }
  val db: AmazonDynamoDB = AmazonDynamoDBClientBuilder.defaultClient()

  def put(id: String, person: Person): Unit = {
    db.putItem(
      new PutItemRequest()
        .withTableName(TableName)
        .withReturnValues(ReturnValue.NONE)
        .withItem(
          Map(
            "id" -> new AttributeValue(id),
            "json" -> new AttributeValue(Json.toJson(person).toString)
          ).asJava
        )
    )
  }

  def get(id: String): Person = {
    val json = db.getItem(TableName, Map("id" -> new AttributeValue(id)).asJava)
      .getItem.get("json").getS
    Json.parse(json).as[Person]
  }
}

@HttpHandler(path = "/person", method = "post")
class PostPerson extends ApiGatewayHandler {
  override def handle(request: HttpRequest, ctx: Context): HttpResponse = {
    println("stage: " + sys.env("STAGE"))
    println("projectName: " + sys.env("PROJECT_NAME"))
    println("version: " + sys.env("VERSION"))
    println(request.body.toString())
    val person = request.bodyOpt[Person].get
    val id = UUID.randomUUID.toString
    PersonRepository.put(id, person)
    HttpResponse(200, Json.toJson(person.copy(id = Option(id))), Map.empty)
  }
}

object PersonId {
  implicit val format: Format[PersonId] = Json.format
}
final case class PersonId(id: String)
@HttpHandler(path = "/person/{id}", method = "get")
class GetPerson extends ApiGatewayHandler {
  override def handle(request: HttpRequest, ctx: Context): HttpResponse = {
    val person = PersonRepository.get(request.pathParamsOpt[PersonId].get.id)
    HttpResponse(200, Json.toJson(person), Map.empty)
  }
}

// dynamodb streams //
object PersonIdsRepository {
  final val TableName = {
    val projectName = sys.env("PROJECT_NAME")
    val stage = sys.env("STAGE")
  }
  val db: AmazonDynamoDB = AmazonDynamoDBClientBuilder.defaultClient()

  def put(id: String): Unit = {
    db.putItem(
      new PutItemRequest()
        .withTableName(TableName)
        .withReturnValues(ReturnValue.NONE)
        .withItem(Map("id" -> new AttributeValue(id)).asJava)
    )
  }

  def list: List[String] = {
    db.scan(TableName, List("id").asJava)
      .getItems.asScala.flatMap(_.values().asScala).map(_.getS).toList
  }
}

object PersonIdStream {
  implicit val reads: Reads[PersonIdStream] = {
    (JsPath \ "id" \ "S").read[String].map(PersonIdStream.apply)
  }
}
final case class PersonIdStream(id: String)
@DynamoHandler(tableName = "People")
class PersonIdDynamoDBHandler extends DynamoDBHandler {
  override def handle(request: DynamoDbRequest, ctx: Context): Unit = {
    request.getInsertedKeys[PersonIdStream].map(_.id).foreach { id =>
      PersonIdsRepository.put(id)
    }
  }
}

@HttpHandler(path = "/personids", method = "get")
class GetPersonIds extends ApiGatewayHandler {
  override def handle(request: HttpRequest, ctx: Context): HttpResponse = {
    HttpResponse(200, Json.toJson(PersonIdsRepository.list), Map.empty)
  }
}

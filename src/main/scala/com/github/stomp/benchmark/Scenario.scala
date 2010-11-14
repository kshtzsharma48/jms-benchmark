/**
 * Copyright (C) 2009-2010 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.stomp.benchmark

import java.util.concurrent.atomic._
import java.util.concurrent.TimeUnit._
import java.net._
import java.io._
import scala.collection.mutable.ListBuffer

/**
 * Simulates load on the a stomp broker.
 */
class Scenario {

  object Constants {
    val MESSAGE_ID:Array[Byte] = "message-id"
    val NEWLINE = '\n'.toByte
    val NANOS_PER_SECOND = NANOSECONDS.convert(1, SECONDS)
  }

  import Constants._

  implicit def toByteBuffer(value: String) = value.getBytes("UTF-8")

  var login:String = _
  var passcode:String = _

  var producer_sleep = 0
  var consumer_sleep = 0
  var producers = 1
  var producers_per_sample = 0
  var consumers = 1
  var consumers_per_sample = 0
  var sample_interval = 1000
  var host = "127.0.0.1"
  var port = 61613
  var buffer_size = 64*1204
  var message_size = 1024
  var content_length=true
  var persistent = false
  var sync_send = false
  var headers = Array[Array[String]]()
  var ack = "auto"
  var selector:String = null
  var durable = false

  var destination_type = "queue"
  var destination_name = "load"
  var destination_count = 1

  val producer_counter = new AtomicLong()
  val consumer_counter = new AtomicLong()
  val error_counter = new AtomicLong()
  val done = new AtomicBoolean()

  var queue_prefix = "/queue/"
  var topic_prefix = "/topic/"
  var name = "custom"

  var client_stack_size = 1024*100;

  private def destination(i:Int) = destination_type match {
    case "queue" => queue_prefix+destination_name+"-"+(i%destination_count)
    case "topic" => topic_prefix+destination_name+"-"+(i%destination_count)
    case _ => throw new Exception("Unsuported destination type: "+destination_type)
  }

  private def headers_for(i:Int) = {
    if ( headers.isEmpty ) {
      Array[String]()
    } else {
      headers(i%headers.size)
    }
  }

  var producer_threads = List[ProducerThread]()
  var consumer_threads = List[ConsumerThread]()

  def with_load[T](func: =>T ):T = {
    done.set(false)
    var producer_threads = List[ProducerThread]()
    for (i <- 0 until producers) {
      val thread = new ProducerThread(i)
      producer_threads ::= thread
      thread.start()
    }

    for (i <- 0 until consumers) {
      val thread = new ConsumerThread(i)
      consumer_threads ::= thread
      thread.start()
    }

    try {
      func
    } finally {
      done.set(true)
      // wait for the threads to finish..
      for( thread <- consumer_threads ) {
        thread.shutdown
      }
      consumer_threads = List()
      for( thread <- producer_threads ) {
        thread.shutdown
      }
      producer_threads = List()
    }
  }

  def drain = {
    done.set(false)
    if( destination_type=="queue" || durable==true ) {
      print("draining")
      consumer_counter.set(0)
      var consumer_threads = List[ConsumerThread]()
      for (i <- 0 until destination_count) {
        val thread = new ConsumerThread(i)
        consumer_threads ::= thread
        thread.start()
      }

      // Keep sleeping until we stop draining messages.
      var drained = 0L
      try {
        Thread.sleep(1000);

        def done() = {
          val c = consumer_counter.getAndSet(0)
          drained += c
          c == 0
        }
        while( !done ) {
          print(".")
          Thread.sleep(500);
        }
      } finally {
        done.set(true)
        for( thread <- consumer_threads ) {
          thread.shutdown
        }
        println(". (drained %d)".format(drained))
      }
    }
  }

  var producer_samples:Option[ListBuffer[Long]] = None
  var consumer_samples:Option[ListBuffer[Long]] = None
  var error_samples = ListBuffer[Long]()

  def collection_start: Unit = {
    producer_counter.set(0)
    consumer_counter.set(0)
    error_counter.set(0)

    producer_samples = if (producers > 0) {
      Some(ListBuffer[Long]())
    } else {
      None
    }
    consumer_samples = if (consumers > 0) {
      Some(ListBuffer[Long]())
    } else {
      None
    }
  }

  def collection_sample: Unit = {

    producer_samples.foreach(_ += producer_counter.getAndSet(0))
    consumer_samples.foreach(_ += consumer_counter.getAndSet(0))
    error_samples += error_counter.getAndSet(0)

    // we might need to increment number the producers..
    for (i <- 0 until producers_per_sample) {
      val thread = new ProducerThread(producer_threads.length)
      producer_threads ::= thread
      thread.start()
    }

    // we might need to increment number the consumers..
    for (i <- 0 until consumers_per_sample) {
      val thread = new ConsumerThread(consumer_threads.length)
      consumer_threads ::= thread
      thread.start()
    }

  }

  def collection_end: Map[String, scala.List[Long]] = {
    var rc = Map[String, List[Long]]()
    producer_samples.foreach{ samples =>
      rc += "p_"+name -> samples.toList
      samples.clear
    }
    consumer_samples.foreach{ samples =>
      rc += "c_"+name -> samples.toList
      samples.clear
    }
    rc += "e_"+name -> error_samples.toList
    error_samples.clear
    rc
  }


  /**
   * A simple stomp client used for testing purposes
   */
  class StompClient {

    var socket:Socket = new Socket
    var out:OutputStream = null
    var in:InputStream = null
    val buffer_size = 64*1204

    def open(host: String, port: Int) = {
      socket = new Socket
      socket.connect(new InetSocketAddress(host, port))
      socket.setSoLinger(true, 0)
      out = new BufferedOutputStream(socket.getOutputStream, buffer_size)
      in = new BufferedInputStream(socket.getInputStream, buffer_size)
    }

    def close() = {
      socket.close
    }

    def write(data:Array[Byte]*) = {
      data.foreach(out.write(_))
      out.write(0)
      out.write('\n')
      out.flush
    }

    def skip():Unit = {
      var c = in.read
      while( c >= 0 ) {
        if( c==0 ) {
          return
        }
        c = in.read()
      }
      throw new EOFException()
    }

    def receive():Array[Byte] = {
      var start = true;
      val buffer = new ByteArrayOutputStream()
      var c = in.read
      while( c >= 0 ) {
        if( c==0 ) {
          return buffer.toByteArray
        }
        if( !start || c!= NEWLINE) {
          start = false
          buffer.write(c)
        }
        c = in.read()
      }
      throw new EOFException()
    }

    def receive(expect:Array[Byte]):Array[Byte] = {
      val rc = receive()
      if( !rc.startsWith(expect) ) {
        throw new Exception("Expected "+expect)
      }
      rc
    }

  }

  private def o[T](value:T):Option[T] = value match {
    case null => None
    case x => Some(x)
  }

  class ClientSupport extends Thread(Thread.currentThread.getThreadGroup, null, "client", client_stack_size) {

    var client:StompClient=new StompClient()

    def connect(proc: =>Unit ) = {
      try {
        client.open(host, port)
        client.write("CONNECT\n%s%s\n".format(
          o(login).map("login:%s\n".format(_)).getOrElse(""),
          o(passcode).map("passcode:%s\n".format(_)).getOrElse("")
        ))
        client.receive ("CONNECTED")
        proc
      } catch {
        case e: Throwable =>
          if(!done.get) {
            println("failure occured: "+e)
            error_counter.incrementAndGet
            try {
              Thread.sleep(1000)
            } catch {
              case _ => // ignore
            }
          }
      } finally {
        try {
          client.close()
        } catch {
          case ignore: Throwable =>
        }
      }
    }

    def shutdown = {
      interrupt
      client.close
      join
    }

  }

  class ProducerThread(val id: Int) extends ClientSupport {
    val name: String = "producer " + id
    val content = ("SEND\n" +
              "destination:"+destination(id)+"\n"+
               { if(persistent) "persistent:true\n" else "" } +
               { if(sync_send) "receipt:xxx\n" else "" } +
               { headers_for(id).foldLeft("") { case (sum, v)=> sum+v+"\n" } } +
               { if(content_length) "content-length:"+message_size+"\n" else "" } +
              "\n"+message(name)).getBytes("UTF-8")


    override def run() {
      while (!done.get) {
        connect {
          this.client=client
          var i =0
          while (!done.get) {
            client.write(content)
            if( sync_send ) {
              // waits for the reply..
              client.skip
            }
            producer_counter.incrementAndGet()
            if(producer_sleep > 0) {
              Thread.sleep(producer_sleep)
            }
            i += 1
          }
        }
      }
    }
  }

  def message(name:String) = {
    val buffer = new StringBuffer(message_size)
    buffer.append("Message from " + name+"\n")
    for( i <- buffer.length to message_size ) {
      buffer.append(('a'+(i%26)).toChar)
    }
    var rc = buffer.toString
    if( rc.length > message_size ) {
      rc.substring(0, message_size)
    } else {
      rc
    }
  }

  class ConsumerThread(val id: Int) extends ClientSupport {
    val name: String = "producer " + id

    override def run() {
      while (!done.get) {
        connect {
          client.write(
            "SUBSCRIBE\n" +
             (if(!durable) {""} else {"id:durable:mysub-"+id+"\n"}) +
             (if(selector==null) {""} else {"selector: "+selector+"\n"}) +
             "ack:"+ack+"\n"+
             "destination:"+destination(id)+"\n"+
             "\n")

          receive_loop
        }
      }
    }


    def index_of(haystack:Array[Byte], needle:Array[Byte]):Int = {
      var i = 0
      while( haystack.length >= i+needle.length ) {
        if( haystack.startsWith(needle, i) ) {
          return i
        }
        i += 1
      }
      return -1
    }




    def receive_loop() = {
      val clientAck = ack == "client"
      while (!done.get) {
        if( clientAck ) {
          val msg = client.receive()
          val start = index_of(msg, MESSAGE_ID)
          assert( start >= 0 )
          val end = msg.indexOf("\n", start)
          val msgId = msg.slice(start+MESSAGE_ID.length+1, end)
          client.write("""
ACK
message-id:""", msgId,"""

""")

        } else {
          client.skip
        }
        consumer_counter.incrementAndGet()
        Thread.sleep(consumer_sleep)
      }
    }
  }

  def run() = {
    print(toString)
    println("--------------------------------------")
    println("     Running: Press ENTER to stop")
    println("--------------------------------------")
    println("")

    with_load {

      // start a sampling thread...
      val sample_thread = new Thread() {
        override def run() = {

          def print_rate(name: String, periodCount:Long, totalCount:Long, nanos: Long) = {
            val rate_per_second: java.lang.Float = ((1.0f * periodCount / nanos) * NANOS_PER_SECOND)
            println("%s total: %,d, rate: %,.3f per second".format(name, totalCount, rate_per_second))
          }

          try {
            var start = System.nanoTime
            var total_producer_count = 0L
            var total_consumer_count = 0L
            var total_error_count = 0L
            collection_start
            while( !done.get ) {
              Thread.sleep(sample_interval)
              val end = System.nanoTime
              collection_sample
              val samples = collection_end
              samples.get("p_custom").foreach { case List(count:Long) =>
                total_producer_count += count
                print_rate("Producer", count, total_producer_count, end - start)
              }
              samples.get("c_custom").foreach { case List(count:Long) =>
                total_consumer_count += count
                print_rate("Consumer", count, total_producer_count, end - start)
              }
              samples.get("e_custom").foreach { case List(count:Long) =>
                if( count!= 0 ) {
                  total_error_count += count
                  print_rate("Error", count, total_error_count, end - start)
                }
              }
              start = end
            }
          } catch {
            case e:InterruptedException =>
          }
        }
      }
      sample_thread.start()

      System.in.read()
      done.set(true)

      sample_thread.interrupt
      sample_thread.join
    }

  }

  override def toString() = {
    "--------------------------------------\n"+
    "Scenario Settings\n"+
    "--------------------------------------\n"+
    "  host                  = "+host+"\n"+
    "  port                  = "+port+"\n"+
    "  destination_type      = "+destination_type+"\n"+
    "  queue_prefix          = "+queue_prefix+"\n"+
    "  topic_prefix          = "+topic_prefix+"\n"+
    "  destination_count     = "+destination_count+"\n" +
    "  destination_name      = "+destination_name+"\n" +
    "  sample_interval (ms)  = "+sample_interval+"\n" +
    "  \n"+
    "  --- Producer Properties ---\n"+
    "  producers             = "+producers+"\n"+
    "  message_size          = "+message_size+"\n"+
    "  persistent            = "+persistent+"\n"+
    "  sync_send             = "+sync_send+"\n"+
    "  content_length        = "+content_length+"\n"+
    "  producer_sleep (ms)   = "+producer_sleep+"\n"+
    "  headers               = "+headers+"\n"+
    "  \n"+
    "  --- Consumer Properties ---\n"+
    "  consumers             = "+consumers+"\n"+
    "  consumer_sleep (ms)   = "+consumer_sleep+"\n"+
    "  ack                   = "+ack+"\n"+
    "  selector              = "+selector+"\n"+
    "  durable               = "+durable+"\n"+
    ""

  }

}

/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved. 
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
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.tso;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;

import com.yahoo.omid.client.PingCallback;
import com.yahoo.omid.client.PingPongCallback;
import com.yahoo.omid.client.TSOClient;
import com.yahoo.omid.tso.messages.AbortedTransactionReport;
import com.yahoo.omid.tso.messages.CommitQueryRequest;
import com.yahoo.omid.tso.messages.CommitQueryResponse;
import com.yahoo.omid.tso.messages.CommitRequest;
import com.yahoo.omid.tso.messages.CommitResponse;
import com.yahoo.omid.tso.messages.TimestampResponse;
import com.yahoo.omid.tso.messages.CommittedTransactionReport;
import com.yahoo.omid.tso.messages.PrepareCommit;
import com.yahoo.omid.tso.messages.PrepareResponse;
import com.yahoo.omid.tso.messages.FullAbortReport;
import com.yahoo.omid.tso.messages.LargestDeletedTimestampReport;
import com.yahoo.omid.tso.messages.TimestampRequest;

/**
 * Example of ChannelHandler for the Transaction Client
 * 
 * @author maysam
 * 
 */
public class TestClientHandler extends TSOClient {

   private static final Logger logger = Logger.getLogger(TestClientHandler.class.getName());

   /**
    * Return value for the caller
    */
   final BlockingQueue<Boolean> answer = new LinkedBlockingQueue<Boolean>();
   final BlockingQueue<TSOMessage> messageQueue = new LinkedBlockingQueue<TSOMessage>();
   
   private Channel channel;

   // Sends FullAbortReport upon receiving a CommitResponse with committed =
   // false
   private boolean autoFullAbort = true;
   
   public TestClientHandler(java.util.Properties conf) throws IOException {
      super(conf);
   }

   /**
    * Method to wait for the final response
    * 
    * @return success or not
    */
   public boolean waitForAll() {
      for (;;) {
         try {
            return answer.take();
         } catch (InterruptedException e) {
            // Ignore.
         }
      }
   }

   public void sendMessage(Object msg) throws IOException {
      if (msg instanceof CommitRequest) {
         CommitRequest cr = (CommitRequest) msg;
         commit(cr.startTimestamp, cr, new PingPongCallback<CommitResponse>());
      } else if (msg instanceof TimestampRequest) {
         getNewTimestamp(new PingPongCallback<TimestampResponse>());
      } else if (msg instanceof CommitQueryRequest) {
         CommitQueryRequest cqr = (CommitQueryRequest) msg;
         isCommitted(cqr.startTimestamp, cqr.queryTimestamp, new PingPongCallback<CommitQueryResponse>());
      } else if (msg instanceof FullAbortReport) {
         FullAbortReport atr = (FullAbortReport) msg;
         completeAbort(atr.startTimestamp, new PingCallback());
      } else if (msg instanceof PrepareCommit) {
         PrepareCommit atr = (PrepareCommit) msg;
         prepareCommit(atr.startTimestamp, atr, new PingPongCallback<PrepareResponse>());
      }
   }

   public void clearMessages() {
      messageQueue.clear();
   }
   
   public void await() {
      synchronized(this) {
         while (channel == null) {
            try {
               wait();
            } catch (InterruptedException e) {
               return;
            }
         }
      }
   }
   
   @Override
   protected void processMessage(TSOMessage msg) {
      if (msg instanceof CommitResponse) {
         CommitResponse cr = (CommitResponse) msg;
         if (!cr.committed && autoFullAbort) {
            try {
               completeAbort(cr.startTimestamp, new PingCallback());
            } catch (IOException e) {
               logger.log(Level.SEVERE, "Could not send Abort Complete mesagge.", e.getCause());
            }
         }
      }
      messageQueue.add(msg);
   }

   public void receiveBootstrap() {
      receiveMessage(CommittedTransactionReport.class);
      // Receive all AbortedTransactionReports + one FullAbortReport
      while (receiveMessage() instanceof AbortedTransactionReport);
      receiveMessage(LargestDeletedTimestampReport.class);
   }

   public Object receiveMessage() {
      try {
         Object msg = messageQueue.poll(5, TimeUnit.SECONDS);
         assertNotNull("Reception of message timed out", msg);
         return msg;
      } catch (InterruptedException e) {
         return null;
      }
   }

   public <T extends TSOMessage> T skipUntilReceiveMessage(Class<T> type) {
       Object msg;
       do {
           msg = receiveMessage();
           if (type.isInstance(msg))
               return (T) msg;
       } while (msg != null);
       return null;
   }

   @SuppressWarnings("unchecked")
   public <T extends TSOMessage> T receiveMessage(Class<T> type) {
      try {
         TSOMessage msg = messageQueue.poll(5000, TimeUnit.SECONDS);
         assertNotNull("Reception of message timed out", msg);
         assertThat(msg, is(type));
         return (T) msg;
      } catch (InterruptedException e) {
         return null;
      }
   }

   /**
    * Starts the traffic
    */
   @Override
   public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
      super.channelConnected(ctx, e);
      logger.log(Level.INFO, "Start sending traffic");
      synchronized (this) {
         this.channel = ctx.getChannel();
         notify();
      }

   }

   /**
    * When the channel is closed, print result
    */
   @Override
   public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
   }

   @Override
   public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
      if (e.getCause() instanceof IOException) {
         logger.log(Level.WARNING, "IOException from downstream.");
      } else {
         logger.log(Level.WARNING, "Unexpected exception from downstream.", e.getCause());
      }
      // Offer default object
      answer.offer(false);
      Channels.close(e.getChannel());
   }

   public boolean isAutoFullAbort() {
      return autoFullAbort;
   }

   public void setAutoFullAbort(boolean autoFullAbort) {
      this.autoFullAbort = autoFullAbort;
   }
}

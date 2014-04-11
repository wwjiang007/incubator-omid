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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yahoo.omid.tso.persistence.LoggerAsyncCallback.LoggerInitCallback;
import com.yahoo.omid.tso.persistence.LoggerAsyncCallback.AddRecordCallback;
import com.yahoo.omid.tso.persistence.LoggerException;
import com.yahoo.omid.tso.persistence.StateLogger;


/**
 * The wrapper for different states of TSO: it maintains the following data structures:
 * <ul>
 * <li> committed rows
 * <li> uncommitted transactions
 * <li> the low watermark corresponding to the oldest transaction managed by the TSO
 * </ul>
 * 
 * It also maintains a reference to the state WAL, along with a queue of outstanding changes 
 * to first persist in the WAL and second reply to clients
 * 
 * Also 
 * 
 * 
 * This state is shared between handlers
 * @author maysam
 */
public class TSOState {
    private static final Logger LOG = LoggerFactory.getLogger(TSOState.class);

   /**
    * The maximum entries kept in TSO
    */
   // static final public int MAX_ITEMS = 10000000;
   // static final public int MAX_ITEMS = 4000000;
   static public int MAX_ITEMS = 100000;
   static {
      try {
         MAX_ITEMS = Integer.valueOf(System.getProperty("omid.maxItems"));
      } catch (Exception e) {
         // ignore, usedefault
      }
   };

   static public int MAX_COMMITS = 100000;
   static {
      try {
         MAX_COMMITS = Integer.valueOf(System.getProperty("omid.maxCommits"));
      } catch (Exception e) {
         // ignore, usedefault
      }
   };

   static public int FLUSH_TIMEOUT = 10;
   static {
      try {
         FLUSH_TIMEOUT = Integer.valueOf(System.getProperty("omid.flushTimeout"));
      } catch (Exception e) {
         // ignore, usedefault
      }
   };

   /**
    * Hash map load factor
    */
   static final public float LOAD_FACTOR = 0.5f;
    
   /**
    * Object that implements the logic to log records
    * for recoverability
    */
   
    StateLogger logger = new StateLogger() {
            @Override
            public void initialize(LoggerInitCallback cb, Object ctx) throws LoggerException {
                cb.loggerInitComplete(LoggerException.Code.OK, this, ctx);
            }

            @Override
            public void addRecord(byte[] record, AddRecordCallback cb, Object ctx) {
                cb.addRecordComplete(LoggerException.Code.OK, ctx);
            }

            @Override
            public void shutdown() {}
        };

   public StateLogger getLogger(){
       return logger;
   }
   
   public void setLogger(StateLogger logger){
       this.logger = logger;
   }
   
   /**
    * Only timestamp oracle instance in the system.
    */
   private TimestampOracle timestampOracle;
   
   protected TimestampOracle getSO(){
       return timestampOracle;
   }
   
   /**
    * Largest Deleted Timestamp
    */
   public long largestDeletedTimestamp = 0;
   public long previousLargestDeletedTimestamp = 0;
   
   /**
    * The hash map to to keep track of recently committed rows
    * each bucket is about 20 byte, so the initial capacity is 20MB
    */
   public CommitHashMap hashmap;

   public Uncommitted uncommited;

   /**
    * Process commit request.
    * 
    * @param startTimestamp
    */
   protected long processCommit(long startTimestamp, long commitTimestamp){
       return hashmap.setCommittedTimestamp(startTimestamp, commitTimestamp);
   }
   
   /**
    * Process largest deleted timestamp.
    * 
    * @param largestDeletedTimestamp
    */
   protected synchronized void processLargestDeletedTimestamp(long largestDeletedTimestamp){
       this.largestDeletedTimestamp = Math.max(largestDeletedTimestamp, this.largestDeletedTimestamp);
   }
   
   /**
    * Process abort request.
    * 
    * @param startTimestamp
    */
   protected void processAbort(long startTimestamp) {
       hashmap.setHalfAborted(startTimestamp);
       uncommited.abort(startTimestamp);
   }

   protected void addExistingAbort(long startTimestamp) {
       hashmap.setHalfAborted(startTimestamp);
   }
   
   /**
    * Process full abort report.
    * 
    * @param startTimestamp
    */
   protected void processFullAbort(long startTimestamp){
       hashmap.setFullAborted(startTimestamp);
   }

   /**
    * If logger is disabled, then this call is a noop.
    * 
    * @param record
    * @param cb
    * @param ctx
    */
   public void addRecord(byte[] record, final AddRecordCallback cb, Object ctx) {
       if(logger != null){
           logger.addRecord(record, cb, ctx);
       } else{
           cb.addRecordComplete(LoggerException.Code.OK, ctx);
       }
   }
   
   /**
    * Closes this state object.
    */
   void stop(){
       if(logger != null){
           logger.shutdown();
       }
   }
   
   /*
    * WAL related pointers
    */
   public static int BATCH_SIZE = 1024;//in bytes
   public ByteArrayOutputStream baos = new ByteArrayOutputStream();
   public DataOutputStream toWAL = new DataOutputStream(baos);
   
   public TSOState(StateLogger logger, TimestampOracle timestampOracle) {
       this.timestampOracle = timestampOracle;
       if (logger != null) {
           this.logger = logger;
       }
   }
   
   public TSOState(TimestampOracle timestampOracle) {
       this(null, timestampOracle);
       initialize();
   }

   public void initialize() {
      this.previousLargestDeletedTimestamp = this.timestampOracle.get();
      this.largestDeletedTimestamp = this.previousLargestDeletedTimestamp;
      int bucketSize = closestIntegerPowerOf2(MAX_COMMITS / Math.sqrt(MAX_COMMITS));
      int bucketNumber = closestIntegerPowerOf2(MAX_COMMITS / (double) bucketSize) * 2; 
      this.uncommited = new Uncommitted(timestampOracle.first(), bucketNumber, bucketSize);
      this.hashmap = new CommitHashMap(MAX_ITEMS, largestDeletedTimestamp);
   }

   private int closestIntegerPowerOf2(double d) {
      return (int) Math.pow(2, Math.ceil(Math.max(Math.log(d) / Math.log(2), 1)));
   }
}


/* $Id$ */

/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.manifoldcf.agents.output.amazoncloudsearch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

import java.io.InputStream;
import java.io.IOException;

import org.apache.manifoldcf.core.interfaces.ColumnDescription;
import org.apache.manifoldcf.core.interfaces.IndexDescription;
import org.apache.manifoldcf.core.interfaces.IDBInterface;
import org.apache.manifoldcf.core.interfaces.IResultRow;
import org.apache.manifoldcf.core.interfaces.IResultSet;
import org.apache.manifoldcf.core.interfaces.ManifoldCFException;
import org.apache.manifoldcf.core.interfaces.BinaryInput;
import org.apache.manifoldcf.core.interfaces.TempFileInput;
import org.apache.manifoldcf.core.interfaces.ClauseDescription;
import org.apache.manifoldcf.core.interfaces.UnitaryClause;

public class DocumentChunkManager extends org.apache.manifoldcf.core.database.BaseTable
{
  // Database fields
  private final static String UID_FIELD = "uid";                        // This is the document key, which is a dochash value
  private final static String HOST_FIELD = "serverhost";            // The host and path are there to make sure we don't collide between connections
  private final static String PATH_FIELD = "serverpath";
  private final static String ON_DELETE_FIELD = "ondelete";
  private final static String SDF_DATA_FIELD = "sdfdata";
  
  public DocumentChunkManager(
      IDBInterface database)
  {
    super(database, "amazoncloudsearch_documentdata");
  }

  /** Install the manager 
   * @throws ManifoldCFException 
   */
  public void install() throws ManifoldCFException
  {
    // Standard practice: outer loop on install methods, no transactions
    while (true)
    {
      Map existing = getTableSchema(null,null);
      if (existing == null)
      {
        // Install the table.
        HashMap map = new HashMap();
        map.put(UID_FIELD,new ColumnDescription("VARCHAR(40)",false,false,null,null,false));
        map.put(HOST_FIELD,new ColumnDescription("VARCHAR(255)",false,false,null,null,false));
        map.put(PATH_FIELD,new ColumnDescription("VARCHAR(255)",false,false,null,null,false));
        map.put(ON_DELETE_FIELD,new ColumnDescription("CHAR(1)",false,false,null,null,false));
        map.put(SDF_DATA_FIELD,new ColumnDescription("BLOB",false,true,null,null,false));
        performCreate(map,null);
      }
      else
      {
        // Upgrade code, if needed, goes here
      }

      // Handle indexes, if needed
      IndexDescription keyIndex = new IndexDescription(true,new String[]{HOST_FIELD,PATH_FIELD,UID_FIELD});

      Map indexes = getTableIndexes(null,null);
      Iterator iter = indexes.keySet().iterator();
      while (iter.hasNext())
      {
        String indexName = (String)iter.next();
        IndexDescription id = (IndexDescription)indexes.get(indexName);

        if (keyIndex != null && id.equals(keyIndex))
          keyIndex = null;
        else if (indexName.indexOf("_pkey") == -1)
          // This index shouldn't be here; drop it
          performRemoveIndex(indexName);
      }

      // Add the ones we didn't find
      if (keyIndex != null)
        performAddIndex(null,keyIndex);


      break;
    }
  }
  
  /** Uninstall the manager.
  */
  public void deinstall()
    throws ManifoldCFException
  {
    performDrop(null);
  }
  
  /**
  * Remove document information in table (and make a delete marker).
  * @param uid document uid
  */
  public void removeDocument(String uid, String host, String path)
    throws ManifoldCFException
  {
    while (true)
    {
      long sleepAmt = 0L;
      try
      {
        beginTransaction();
        try
        {

          ArrayList params = new ArrayList();
          String query = buildConjunctionClause(params,new ClauseDescription[]{
            new UnitaryClause(HOST_FIELD,host),
            new UnitaryClause(PATH_FIELD,path),
            new UnitaryClause(UID_FIELD,uid)});

          IResultSet set = performQuery("SELECT "+UID_FIELD+" FROM "+getTableName()+" WHERE "+
            query+" FOR UPDATE",params,null,null);
            
          Map<String,String> parameterMap = new HashMap<String,String>();
          parameterMap.put(ON_DELETE_FIELD, "1");
          parameterMap.put(SDF_DATA_FIELD, null);
            
          //if record exists on table, update record.
          if(set.getRowCount() > 0)
          {
            performUpdate(parameterMap, " WHERE "+query, whereParameters, null);
          }
          else
          {
            parameterMap.put(UID_FIELD, uid);
            parameterMap.put(HOST_FIELD, host);
            parameterMap.put(PATH_FIELD, path);
            performInsert(parameterMap, null);
          }

          break;
        }
        catch (ManifoldCFException e)
        {
          signalRollback();
          throw e;
        }
        catch (RuntimeException e)
        {
          signalRollback();
          throw e;
        }
        catch (Error e)
        {
          signalRollback();
          throw e;
        }
        finally
        {
          endTransaction();
        }
      }
      catch (ManifoldCFException e)
      {
        // Look for deadlock and retry if so
        if (e.getErrorCode() == e.DATABASE_TRANSACTION_ABORT)
        {
          sleepAmt = getSleepAmt();
          continue;
        }
        throw e;
      }
    }
  }
  
  /**
   * Add/replace document information in table.
   * @param uid documentuid
   * @param sdfData document SDF data.
   * @throws ManifoldCFException
   */
  public void addOrReplaceDocument(String uid, String host, String path, InputStream sdfData) 
      throws ManifoldCFException, IOException
  {
    TempFileInput tfi = null;
    try
    {
      // This downloads all the data from upstream!
      try
      {
        tfi = new TempFileInput(sdfData);
      }
      catch (ManifoldCFException e)
      {
        if (e.getErrorCode() == ManifoldCFException.INTERRUPTED)
          throw e;
        throw new IOException("Fetch failed: "+e.getMessage());
      }
      
      while (true)
      {
        long sleepAmt = 0L;
        try
        {
          beginTransaction();
          try
          {

            ArrayList params = new ArrayList();
            String query = buildConjunctionClause(params,new ClauseDescription[]{
              new UnitaryClause(HOST_FIELD,host),
              new UnitaryClause(PATH_FIELD,path),
              new UnitaryClause(UID_FIELD,uid)});

            IResultSet set = performQuery("SELECT "+UID_FIELD+" FROM "+getTableName()+" WHERE "+
              query+" FOR UPDATE",params,null,null);
            
            Map<String,String> parameterMap = new HashMap<String,String>();
            parameterMap.put(ON_DELETE_FIELD, "0");
            parameterMap.put(SDF_DATA_FIELD, tfi);
            
            //if record exists on table, update record.
            if(set.getRowCount() > 0)
            {
              performUpdate(parameterMap, " WHERE "+query, whereParameters, null);
            }
            else
            {
              parameterMap.put(UID_FIELD, uid);
              parameterMap.put(HOST_FIELD, host);
              parameterMap.put(PATH_FIELD, path);
              performInsert(parameterMap, null);
            }
      
            break;
          }
          catch (ManifoldCFException e)
          {
            signalRollback();
            throw e;
          }
          catch (RuntimeException e)
          {
            signalRollback();
            throw e;
          }
          catch (Error e)
          {
            signalRollback();
            throw e;
          }
          finally
          {
            endTransaction();
          }
        }
        catch (ManifoldCFException e)
        {
          // Look for deadlock and retry if so
          if (e.getErrorCode() == e.DATABASE_TRANSACTION_ABORT)
          {
            sleepAmt = getSleepAmt();
            continue;
          }
          throw e;
        }
      }

    }
    finally
    {
      if (tfi != null)
        tfi.discard();
    }
  }
  
  /** Read a chunk of documents.
  */
  public DocumentRecord[] readChunk(String host, String path, int maximumNumber)
    throws ManifoldCFException
  {
    ArrayList params = new ArrayList();
    String query = buildConjunctionClause(params,new ClauseDescription[]{
      new UnitaryClause(HOST_FIELD,host),
      new UnitaryClause(PATH_FIELD,path)});

    IResultSet set = performQuery("SELECT * FROM "+getTableName()+" WHERE "+query+" "+constructOffsetLimitClause(0,maximumNumber),params,null,null);
    DocumentRecord[] rval = new DocumentRecord[set.getRowCount()];
    for (int i = 0; i < set.getRowCount(); i++)
    {
      IResultRow row = set.getRow(i);
      rval[i] = new DocumentRecord(host,path,
        (String)row.getValue(UID_FIELD),
        ((String)row.getValue(ON_DELETE_FIELD)).equals("1"),
        (BinaryInput)row.getValue(SDF_DATA_FIELD));
    }
    return rval;
  }
  
  /** Delete the chunk of documents (presumably because we processed them successfully)
  */
  public void deleteChunk(DocumentRecord[] records)
    throws ManifoldCFException
  {
    // Do the whole thing in a transaction -- if we mess up, we'll have to try everything again
    while (true)
    {
      long sleepAmt = 0L;
      try
      {
        beginTransaction();
        try
        {

          // Theoretically we could aggregate the records, but for now delete one at a time.
          for (DocumentRecord dr : records)
          {
            String host = dr.getHost();
            String path = dr.getPath();
            String uid = dr.getUid();
            ArrayList params = new ArrayList();
            String query = buildConjunctionClause(params,new ClauseDescription[]{
              new UnitaryClause(HOST_FIELD,host),
              new UnitaryClause(PATH_FIELD,path),
              new UnitaryClause(UID_FIELD,uid)});
            performDelete("WHERE "+query,params,null,null);
          }
          
          break;
        }
        catch (ManifoldCFException e)
        {
          signalRollback();
          throw e;
        }
        catch (RuntimeException e)
        {
          signalRollback();
          throw e;
        }
        catch (Error e)
        {
          signalRollback();
          throw e;
        }
        finally
        {
          endTransaction();
        }
      }
      catch (ManifoldCFException e)
      {
        // Look for deadlock and retry if so
        if (e.getErrorCode() == e.DATABASE_TRANSACTION_ABORT)
        {
          sleepAmt = getSleepAmt();
          continue;
        }
        throw e;
      }
    }

  }
  
}

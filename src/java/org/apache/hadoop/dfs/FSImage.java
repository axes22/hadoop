/**
 * Copyright 2005 The Apache Software Foundation
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
package org.apache.hadoop.dfs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.dfs.FSDirectory.INode;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.io.UTF8;
import org.apache.hadoop.io.Writable;

/**
 * FSImage handles checkpointing and logging of the namespace edits.
 * 
 * @author Konstantin Shvachko
 */
class FSImage {
  private static final String FS_IMAGE = "fsimage";
  private static final String NEW_FS_IMAGE = "fsimage.new";
  private static final String OLD_FS_IMAGE = "fsimage.old";

  private File imageDir;  /// directory that contains the image file 
  private FSEditLog editLog;
  // private int namespaceID = 0;    /// a persistent attribute of the namespace

  /**
   * 
   */
  FSImage( File fsDir, Configuration conf ) throws IOException {
    this.imageDir = new File(fsDir, "image");
    if (! imageDir.exists()) {
      throw new IOException("NameNode not formatted: " + fsDir);
    }
    File edits = new File(fsDir, "edits");
    this.editLog = new FSEditLog( edits );
  }
  
  FSEditLog getEditLog() {
    return editLog;
  }

  /**
   * Load in the filesystem image.  It's a big list of
   * filenames and blocks.  Return whether we should
   * "re-save" and consolidate the edit-logs
   */
  void loadFSImage( FSDirectory fsDir, 
                    Configuration conf
                  ) throws IOException {
    File edits = editLog.getEditsFile();
    //
    // Atomic move sequence, to recover from interrupted save
    //
    File curFile = new File(imageDir, FS_IMAGE);
    File newFile = new File(imageDir, NEW_FS_IMAGE);
    File oldFile = new File(imageDir, OLD_FS_IMAGE);

    // Maybe we were interrupted between 2 and 4
    if (oldFile.exists() && curFile.exists()) {
      oldFile.delete();
      if (edits.exists()) {
        edits.delete();
      }
    } else if (oldFile.exists() && newFile.exists()) {
      // Or maybe between 1 and 2
      newFile.renameTo(curFile);
      oldFile.delete();
    } else if (curFile.exists() && newFile.exists()) {
      // Or else before stage 1, in which case we lose the edits
      newFile.delete();
    }

    //
    // Load in bits
    //
    boolean needToSave = true;
    int imgVersion = FSConstants.DFS_CURRENT_VERSION;
    if (curFile.exists()) {
      DataInputStream in = new DataInputStream(
                              new BufferedInputStream(
                                  new FileInputStream(curFile)));
      try {
        // read image version: first appeared in version -1
        imgVersion = in.readInt();
        // read namespaceID: first appeared in version -2
        if( imgVersion <= -2 )
          fsDir.namespaceID = in.readInt();
        // read number of files
        int numFiles = 0;
        // version 0 does not store version #
        // starts directly with the number of files
        if( imgVersion >= 0 ) {  
          numFiles = imgVersion;
          imgVersion = 0;
        } else 
          numFiles = in.readInt();
        
        needToSave = ( imgVersion != FSConstants.DFS_CURRENT_VERSION );
        if( imgVersion < FSConstants.DFS_CURRENT_VERSION ) // future version
          throw new IOException(
              "Unsupported version of the file system image: "
              + imgVersion
              + ". Current version = " 
              + FSConstants.DFS_CURRENT_VERSION + "." );
        
        // read file info
        short replication = (short)conf.getInt("dfs.replication", 3);
        for (int i = 0; i < numFiles; i++) {
          UTF8 name = new UTF8();
          name.readFields(in);
          // version 0 does not support per file replication
          if( !(imgVersion >= 0) ) {
            replication = in.readShort(); // other versions do
            replication = FSEditLog.adjustReplication( replication, conf );
          }
          int numBlocks = in.readInt();
          Block blocks[] = null;
          if (numBlocks > 0) {
            blocks = new Block[numBlocks];
            for (int j = 0; j < numBlocks; j++) {
              blocks[j] = new Block();
              blocks[j].readFields(in);
            }
          }
          fsDir.unprotectedAddFile(name, blocks, replication );
        }
      } finally {
        in.close();
      }
    }
    
    if( fsDir.namespaceID == 0 )
      fsDir.namespaceID = newNamespaceID();
    
    needToSave |= ( edits.exists() && editLog.loadFSEdits(fsDir, conf) > 0 );
    if( needToSave )
      saveFSImage( fsDir );
  }

  /**
   * Save the contents of the FS image
   */
  void saveFSImage( FSDirectory fsDir ) throws IOException {
    File curFile = new File(imageDir, FS_IMAGE);
    File newFile = new File(imageDir, NEW_FS_IMAGE);
    File oldFile = new File(imageDir, OLD_FS_IMAGE);
    
    //
    // Write out data
    //
    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(newFile)));
    try {
      out.writeInt(FSConstants.DFS_CURRENT_VERSION);
      out.writeInt(fsDir.namespaceID);
      out.writeInt(fsDir.rootDir.numItemsInTree() - 1);
      saveImage( "", fsDir.rootDir, out );
    } finally {
      out.close();
    }
    
    //
    // Atomic move sequence
    //
    // 1.  Move cur to old
    curFile.renameTo(oldFile);
    // 2.  Move new to cur
    newFile.renameTo(curFile);
    // 3.  Remove pending-edits file (it's been integrated with newFile)
    editLog.getEditsFile().delete();
    // 4.  Delete old
    oldFile.delete();
  }

  /**
   * Generate new namespaceID.
   * 
   * namespaceID is a persistent attribute of the namespace.
   * It is generated when the namenode is formatted and remains the same
   * during the life cycle of the namenode.
   * When a datanodes register they receive it as the registrationID,
   * which is checked every time the datanode is communicating with the 
   * namenode. Datanodes that do not 'know' the namespaceID are rejected.
   * 
   * @return new namespaceID
   */
  private int newNamespaceID() {
    Random r = new Random();
    r.setSeed( System.currentTimeMillis() );
    int newID = 0;
    while( newID == 0)
      newID = r.nextInt();
    return newID;
  }
  
  /** Create a new dfs name directory.  Caution: this destroys all files
   * in this filesystem. */
  static void format(File dir, Configuration conf) throws IOException {
    File image = new File(dir, "image");
    File edits = new File(dir, "edits");
    
    if (!((!image.exists() || FileUtil.fullyDelete(image)) &&
        (!edits.exists() || edits.delete()) &&
        image.mkdirs())) {
      throw new IOException("Unable to format: "+dir);
    }
  }

  /**
   * Save file tree image starting from the given root.
   */
  void saveImage( String parentPrefix, 
                  FSDirectory.INode root, 
                  DataOutputStream out ) throws IOException {
    String fullName = "";
    if( root.getParent() != null) {
      fullName = parentPrefix + "/" + root.getLocalName();
      new UTF8(fullName).write(out);
      out.writeShort( root.getReplication() );
      if( root.isDir() ) {
        out.writeInt(0);
      } else {
        int nrBlocks = root.getBlocks().length;
        out.writeInt( nrBlocks );
        for (int i = 0; i < nrBlocks; i++)
          root.getBlocks()[i].write(out);
      }
    }
    for(Iterator it = root.getChildren().values().iterator(); it.hasNext(); ) {
      INode child = (INode) it.next();
      saveImage( fullName, child, out );
    }
  }
}

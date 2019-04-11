/*
 * Copyright 2018 StreamSets Inc.
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
package com.streamsets.pipeline.lib.remote;

import net.schmizz.sshj.sftp.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A wrapper around {@link SFTPClient} that acts as if the root of the remote filesystem is at the specified root.
 * In other words, to the outside world it's similar as if you did a 'chroot'.  This is useful for being compatible with
 * {@link org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder} and consistent with
 * {@link org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder}.  It also adds an archive functionality and
 * handles massaging some paths.
 */
public class ChrootSFTPClient {

  private static final int MAX_UNCONFIRMED_READ_WRITES = 64;

  private final String root;
  private String archiveDir;
  private SFTPClient sftpClient;
  private final String pathSeparator;

  /**
   * Wraps the provided {@link SFTPClient} at the given root.  The given root can either be an absolute path or a path
   * relative to the user's home directory.
   *
   * @param sftpClient The {@link SFTPClient} to wrap
   * @param root The root directory to use
   * @param rootRelativeToUserDir true if the given root is relative to the user's home dir, false if not
   * @param makeRoot will create the root dir if true and it doesn't already exist
   * @throws IOException
   */
  public ChrootSFTPClient(SFTPClient sftpClient, String root, boolean rootRelativeToUserDir, boolean makeRoot) throws
      IOException {
    this.sftpClient = sftpClient;
    this.pathSeparator = sftpClient.getSFTPEngine().getPathHelper().getPathSeparator();
    if (rootRelativeToUserDir) {
      root = sftpClient.canonicalize(".") + (root.startsWith(pathSeparator) ? root : pathSeparator + root);
    }
    if (sftpClient.statExistence(root) == null) {
      if (makeRoot) {
        sftpClient.mkdirs(root);
      } else {
        throw new SFTPException(root + " does not exist");
      }
    }
    this.root = root;
  }

  public void setSFTPClient(SFTPClient sftpClient) {
    this.sftpClient = sftpClient;
  }

  private String prependRoot(String path) {
    if (path.startsWith(pathSeparator) && !path.isEmpty()) {
      path = path.substring(1);
    }
    return sftpClient.getSFTPEngine().getPathHelper().adjustForParent(root, path);
  }

  private String prependArchiveDir(String path) {
    return archiveDir + (path.startsWith(pathSeparator) ? path : pathSeparator + path);
  }

  private String removeRoot(String path) {
    return pathSeparator + Paths.get(root).relativize(Paths.get(path)).toString();
  }

  public List<SimplifiedRemoteResourceInfo> ls() throws IOException {
    return ls("/", null);
  }

  public List<SimplifiedRemoteResourceInfo> ls(String path) throws IOException {
    return ls(path, null);
  }

  public List<SimplifiedRemoteResourceInfo> ls(RemoteResourceFilter filter) throws IOException {
    return ls("/", filter);
  }

  public List<SimplifiedRemoteResourceInfo> ls(String path, RemoteResourceFilter filter) throws IOException {
    final RemoteDirectory dir = sftpClient.getSFTPEngine().openDir(prependRoot(path));
    try {
      List<RemoteResourceInfo> dirScan = dir.scan(filter);
      List<SimplifiedRemoteResourceInfo> results = new ArrayList<>(dirScan.size());
      for (RemoteResourceInfo remoteResourceInfo : dirScan) {
        // This is needed in order to remove the root from the paths (RemoteResourceInfo is unfortunately immutable)
        results.add(
            new SimplifiedRemoteResourceInfo(
                removeRoot(remoteResourceInfo.getPath()),
                remoteResourceInfo.getAttributes().getMtime(),
                remoteResourceInfo.getAttributes().getType())
        );
      }
      return results;
    } finally {
      dir.close();
    }
  }

  public InputStream openForReading(String path) throws IOException {
    return sftpClient.open(prependRoot(path)).new ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED_READ_WRITES);
  }

  public OutputStream openForWriting(String path) throws IOException {
    String toPath = prependRoot(path);
    // Create the toPath's parent dir(s) if they don't exist
    String toDir = sftpClient.getSFTPEngine().getPathHelper().getComponents(toPath).getParent();
    sftpClient.mkdirs(toDir);
    return sftpClient.open(
        toPath,
        EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
        FileAttributes.EMPTY
    ).new RemoteFileOutputStream(0, MAX_UNCONFIRMED_READ_WRITES);
  }

  public FileAttributes stat(String path) throws IOException {
    return sftpClient.stat(prependRoot(path));
  }

  public boolean exists(String path) throws IOException {
    return sftpClient.statExistence(prependRoot(path)) != null;
  }

  public void delete(String path) throws IOException {
    sftpClient.rm(prependRoot(path));
  }

  public void setArchiveDir(String archiveDir, boolean archiveDirRelativeToUserDir) throws IOException {
    if (archiveDir != null) {
      if (archiveDirRelativeToUserDir) {
        archiveDir = sftpClient.canonicalize(".") + (
            archiveDir.startsWith(pathSeparator) ? archiveDir : pathSeparator + archiveDir
        );
      }
      if (archiveDir.endsWith(pathSeparator)) {
        archiveDir = archiveDir.substring(0, archiveDir.length() - 1);
      }
    }
    this.archiveDir = archiveDir;
  }

  public String archive(String path) throws IOException {
    if (archiveDir == null) {
      throw new IOException("No archive directory defined - cannot archive");
    }
    String fromPath = prependRoot(path);
    String toPath = prependArchiveDir(path);
    // Create the toPath's parent dir(s) if they don't exist
    String toDir = sftpClient.getSFTPEngine().getPathHelper().getComponents(toPath).getParent();
    sftpClient.mkdirs(toDir);
    sftpClient.rename(fromPath, toPath);
    return toPath;
  }

  public void close() throws IOException {
    sftpClient.close();
  }

  public static class SimplifiedRemoteResourceInfo {
    private String path;
    private long modifiedTime;
    private FileMode.Type type;

    public SimplifiedRemoteResourceInfo(String path, long modifiedTime, FileMode.Type type) {
      this.path = path;
      this.modifiedTime = modifiedTime;
      this.type = type;
    }

    public String getPath() {
      return path;
    }

    public long getModifiedTime() {
      return modifiedTime;
    }

    public FileMode.Type getType() {
      return type;
    }
  }
}

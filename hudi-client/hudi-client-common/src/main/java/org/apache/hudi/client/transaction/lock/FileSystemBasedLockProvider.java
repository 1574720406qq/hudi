/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hudi.client.transaction.lock;

import org.apache.hudi.common.config.HoodieCommonConfig;
import org.apache.hudi.common.config.LockConfiguration;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.common.lock.LockProvider;
import org.apache.hudi.common.lock.LockState;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.util.FileIOUtils;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.common.util.ValidationUtils;
import org.apache.hudi.config.HoodieLockConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.HoodieLockException;
import org.apache.hudi.hadoop.fs.HadoopFSUtils;
import org.apache.hudi.storage.HoodieLocation;
import org.apache.hudi.storage.StorageSchemes;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.hudi.common.config.LockConfiguration.FILESYSTEM_LOCK_EXPIRE_PROP_KEY;
import static org.apache.hudi.common.config.LockConfiguration.FILESYSTEM_LOCK_PATH_PROP_KEY;
import static org.apache.hudi.common.table.HoodieTableMetaClient.AUXILIARYFOLDER_NAME;

/**
 * A FileSystem based lock. This {@link LockProvider} implementation allows to lock table operations
 * using DFS. Users might need to manually clean the Locker's path if writeClient crash and never run again.
 * NOTE: This only works for DFS with atomic create/delete operation
 */
public class FileSystemBasedLockProvider implements LockProvider<String>, Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(FileSystemBasedLockProvider.class);
  private static final String LOCK_FILE_NAME = "lock";
  private final int lockTimeoutMinutes;
  private final transient FileSystem fs;
  private final transient Path lockFile;
  protected LockConfiguration lockConfiguration;
  private SimpleDateFormat sdf;
  private LockInfo lockInfo;
  private String currentOwnerLockInfo;

  public FileSystemBasedLockProvider(final LockConfiguration lockConfiguration, final Configuration configuration) {
    checkRequiredProps(lockConfiguration);
    this.lockConfiguration = lockConfiguration;
    String lockDirectory = lockConfiguration.getConfig().getString(FILESYSTEM_LOCK_PATH_PROP_KEY, null);
    if (StringUtils.isNullOrEmpty(lockDirectory)) {
      lockDirectory = lockConfiguration.getConfig().getString(HoodieWriteConfig.BASE_PATH.key())
          + HoodieLocation.SEPARATOR + HoodieTableMetaClient.METAFOLDER_NAME;
    }
    this.lockTimeoutMinutes = lockConfiguration.getConfig().getInteger(FILESYSTEM_LOCK_EXPIRE_PROP_KEY);
    this.lockFile = new Path(lockDirectory + HoodieLocation.SEPARATOR + LOCK_FILE_NAME);
    this.lockInfo = new LockInfo();
    this.sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    this.fs = HadoopFSUtils.getFs(this.lockFile.toString(), configuration);
    List<String> customSupportedFSs = lockConfiguration.getConfig().getStringList(HoodieCommonConfig.HOODIE_FS_ATOMIC_CREATION_SUPPORT.key(), ",", new ArrayList<>());
    if (!customSupportedFSs.contains(this.fs.getScheme()) && !StorageSchemes.isAtomicCreationSupported(this.fs.getScheme())) {
      throw new HoodieLockException("Unsupported scheme :" + this.fs.getScheme() + ", since this fs can not support atomic creation");
    }
  }

  @Override
  public void close() {
    synchronized (LOCK_FILE_NAME) {
      try {
        fs.delete(this.lockFile, true);
      } catch (IOException e) {
        throw new HoodieLockException(generateLogStatement(LockState.FAILED_TO_RELEASE), e);
      }
    }
  }

  @Override
  public boolean tryLock(long time, TimeUnit unit) {
    try {
      synchronized (LOCK_FILE_NAME) {
        // Check whether lock is already expired, if so try to delete lock file
        if (fs.exists(this.lockFile)) {
          if (checkIfExpired()) {
            fs.delete(this.lockFile, true);
            LOG.warn("Delete expired lock file: " + this.lockFile);
          } else {
            reloadCurrentOwnerLockInfo();
            return false;
          }
        }
        acquireLock();
        return fs.exists(this.lockFile);
      }
    } catch (IOException | HoodieIOException e) {
      LOG.info(generateLogStatement(LockState.FAILED_TO_ACQUIRE), e);
      return false;
    }
  }

  @Override
  public void unlock() {
    synchronized (LOCK_FILE_NAME) {
      try {
        if (fs.exists(this.lockFile)) {
          fs.delete(this.lockFile, true);
        }
      } catch (IOException io) {
        throw new HoodieIOException(generateLogStatement(LockState.FAILED_TO_RELEASE), io);
      }
    }
  }

  @Override
  public String getLock() {
    return this.lockFile.toString();
  }

  @Override
  public String getCurrentOwnerLockInfo() {
    return currentOwnerLockInfo;
  }

  private boolean checkIfExpired() {
    if (lockTimeoutMinutes == 0) {
      return false;
    }
    try {
      long modificationTime = fs.getFileStatus(this.lockFile).getModificationTime();
      if (System.currentTimeMillis() - modificationTime > lockTimeoutMinutes * 60 * 1000L) {
        return true;
      }
    } catch (IOException | HoodieIOException e) {
      LOG.error(generateLogStatement(LockState.ALREADY_RELEASED) + " failed to get lockFile's modification time", e);
    }
    return false;
  }

  private void acquireLock() {
    try (FSDataOutputStream fos = fs.create(this.lockFile, false)) {
      if (!fs.exists(this.lockFile)) {
        initLockInfo();
        fos.writeBytes(lockInfo.toString());
      }
    } catch (IOException e) {
      throw new HoodieIOException(generateLogStatement(LockState.FAILED_TO_ACQUIRE), e);
    }
  }

  public void initLockInfo() {
    lockInfo.setLockCreateTime(sdf.format(System.currentTimeMillis()));
    lockInfo.setLockThreadName(Thread.currentThread().getName());
    lockInfo.setLockStacksInfo(Thread.currentThread().getStackTrace());
  }

  public void reloadCurrentOwnerLockInfo() {
    try (FSDataInputStream fis = fs.open(this.lockFile)) {
      if (fs.exists(this.lockFile)) {
        this.currentOwnerLockInfo = FileIOUtils.readAsUTFString(fis);
      } else {
        this.currentOwnerLockInfo = "";
      }
    } catch (IOException e) {
      throw new HoodieIOException(generateLogStatement(LockState.FAILED_TO_ACQUIRE), e);
    }
  }

  protected String generateLogStatement(LockState state) {
    return StringUtils.join(state.name(), " lock at: ", getLock());
  }

  private void checkRequiredProps(final LockConfiguration config) {
    ValidationUtils.checkArgument(config.getConfig().getString(FILESYSTEM_LOCK_PATH_PROP_KEY, null) != null
        || config.getConfig().getString(HoodieWriteConfig.BASE_PATH.key(), null) != null);
    ValidationUtils.checkArgument(config.getConfig().getInteger(FILESYSTEM_LOCK_EXPIRE_PROP_KEY) >= 0);
  }

  /**
   * Returns a filesystem based lock config with given table path.
   */
  public static TypedProperties getLockConfig(String tablePath) {
    TypedProperties props = new TypedProperties();
    props.put(HoodieLockConfig.LOCK_PROVIDER_CLASS_NAME.key(), FileSystemBasedLockProvider.class.getName());
    props.put(HoodieLockConfig.LOCK_ACQUIRE_WAIT_TIMEOUT_MS.key(), "2000");
    props.put(HoodieLockConfig.FILESYSTEM_LOCK_EXPIRE.key(), "1");
    props.put(HoodieLockConfig.LOCK_ACQUIRE_CLIENT_NUM_RETRIES.key(), "30");
    props.put(HoodieLockConfig.FILESYSTEM_LOCK_PATH.key(), defaultLockPath(tablePath));
    return props;
  }

  /**
   * Returns the default lock file root path.
   *
   * <p>IMPORTANT: this path should be shared especially when there is engine cooperation.
   */
  private static String defaultLockPath(String tablePath) {
    return tablePath + HoodieLocation.SEPARATOR + AUXILIARYFOLDER_NAME;
  }
}

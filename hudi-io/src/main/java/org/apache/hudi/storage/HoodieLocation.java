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

package org.apache.hudi.storage;

import org.apache.hudi.ApiMaturityLevel;
import org.apache.hudi.PublicAPIClass;
import org.apache.hudi.PublicAPIMethod;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Names a file or directory on storage.
 * Location strings use slash (`/`) as the directory separator.
 * The APIs are mainly based on {@code org.apache.hadoop.fs.Path} class.
 */
@PublicAPIClass(maturity = ApiMaturityLevel.EVOLVING)
public class HoodieLocation implements Comparable<HoodieLocation>, Serializable {
  public static final char SEPARATOR_CHAR = '/';
  public static final char COLON_CHAR = ':';
  public static final String SEPARATOR = "" + SEPARATOR_CHAR;
  private final URI uri;
  private transient volatile HoodieLocation cachedParent;
  private transient volatile String cachedName;
  private transient volatile String uriString;

  public HoodieLocation(URI uri) {
    this.uri = uri.normalize();
  }

  public HoodieLocation(String path) {
    try {
      // This part of parsing is compatible with hadoop's Path
      // and required for properly handling encoded path with URI
      String scheme = null;
      String authority = null;

      int start = 0;

      // Parse URI scheme, if any
      int colon = path.indexOf(COLON_CHAR);
      int slash = path.indexOf(SEPARATOR_CHAR);
      if (colon != -1
          && ((slash == -1) || (colon < slash))) {
        scheme = path.substring(0, colon);
        start = colon + 1;
      }

      // Parse URI authority, if any
      if (path.startsWith("//", start)
          && (path.length() - start > 2)) {
        int nextSlash = path.indexOf(SEPARATOR_CHAR, start + 2);
        int authEnd = nextSlash > 0 ? nextSlash : path.length();
        authority = path.substring(start + 2, authEnd);
        start = authEnd;
      }

      // URI path is the rest of the string -- query & fragment not supported
      String uriPath = path.substring(start);

      this.uri = new URI(scheme, authority, normalize(uriPath, true), null, null).normalize();
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public HoodieLocation(String parent, String child) {
    this(new HoodieLocation(parent), child);
  }

  public HoodieLocation(HoodieLocation parent, String child) {
    URI parentUri = parent.toUri();
    String normalizedChild = normalize(child, false);

    if (normalizedChild.isEmpty()) {
      this.uri = parentUri;
      return;
    }

    if (!child.contains(SEPARATOR)) {
      this.cachedParent = parent;
    }
    String parentPathWithSeparator = parentUri.getPath();
    if (!parentPathWithSeparator.endsWith(SEPARATOR)) {
      parentPathWithSeparator = parentPathWithSeparator + SEPARATOR;
    }
    try {
      URI resolvedUri = new URI(
          parentUri.getScheme(),
          parentUri.getAuthority(),
          parentPathWithSeparator,
          null,
          parentUri.getFragment()).resolve(normalizedChild);
      this.uri = new URI(
          parentUri.getScheme(),
          parentUri.getAuthority(),
          resolvedUri.getPath(),
          null,
          resolvedUri.getFragment()).normalize();
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @PublicAPIMethod(maturity = ApiMaturityLevel.EVOLVING)
  public boolean isAbsolute() {
    return uri.getPath().startsWith(SEPARATOR);
  }

  @PublicAPIMethod(maturity = ApiMaturityLevel.EVOLVING)
  public HoodieLocation getParent() {
    // This value could be overwritten concurrently and that's okay, since
    // {@code HoodieLocation} is immutable
    if (cachedParent == null) {
      String path = uri.getPath();
      int lastSlash = path.lastIndexOf(SEPARATOR_CHAR);
      if (path.isEmpty() || path.equals(SEPARATOR)) {
        throw new IllegalStateException("Cannot get parent location of a root location");
      }
      String parentPath = lastSlash == -1
          ? "" : path.substring(0, lastSlash == 0 ? 1 : lastSlash);
      try {
        cachedParent = new HoodieLocation(new URI(
            uri.getScheme(), uri.getAuthority(), parentPath, null, uri.getFragment()));
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException(e);
      }
    }
    return cachedParent;
  }

  @PublicAPIMethod(maturity = ApiMaturityLevel.EVOLVING)
  public String getName() {
    // This value could be overwritten concurrently and that's okay, since
    // {@code HoodieLocation} is immutable
    if (cachedName == null) {
      String path = uri.getPath();
      int slash = path.lastIndexOf(SEPARATOR);
      cachedName = path.substring(slash + 1);
    }
    return cachedName;
  }

  @PublicAPIMethod(maturity = ApiMaturityLevel.EVOLVING)
  public HoodieLocation getLocationWithoutSchemeAndAuthority() {
    try {
      return new HoodieLocation(
          new URI(null, null, uri.getPath(), uri.getQuery(), uri.getFragment()));
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @PublicAPIMethod(maturity = ApiMaturityLevel.EVOLVING)
  public int depth() {
    String path = uri.getPath();
    int depth = 0;
    int slash = path.length() == 1 && path.charAt(0) == SEPARATOR_CHAR ? -1 : 0;
    while (slash != -1) {
      depth++;
      slash = path.indexOf(SEPARATOR_CHAR, slash + 1);
    }
    return depth;
  }

  @PublicAPIMethod(maturity = ApiMaturityLevel.EVOLVING)
  public URI toUri() {
    return uri;
  }

  @Override
  public String toString() {
    // This value could be overwritten concurrently and that's okay, since
    // {@code HoodieLocation} is immutable
    if (uriString == null) {
      // We can't use uri.toString(), which escapes everything, because we want
      // illegal characters unescaped in the string, for glob processing, etc.
      StringBuilder buffer = new StringBuilder();
      if (uri.getScheme() != null) {
        buffer.append(uri.getScheme())
            .append(":");
      }
      if (uri.getAuthority() != null) {
        buffer.append("//")
            .append(uri.getAuthority());
      }
      if (uri.getPath() != null) {
        String path = uri.getPath();
        buffer.append(path);
      }
      if (uri.getFragment() != null) {
        buffer.append("#").append(uri.getFragment());
      }
      uriString = buffer.toString();
    }
    return uriString;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof HoodieLocation)) {
      return false;
    }
    return this.uri.equals(((HoodieLocation) o).toUri());
  }

  @Override
  public int hashCode() {
    return uri.hashCode();
  }

  @Override
  public int compareTo(HoodieLocation o) {
    return this.uri.compareTo(o.uri);
  }

  /**
   * Normalizes the path by removing the trailing slashes (`/`).
   * When {@code keepSingleSlash} is {@code true}, `/` as the path is not changed;
   * otherwise ({@code false}), `/` becomes empty String after normalization.
   *
   * @param path            {@link String} path to normalize.
   * @param keepSingleSlash whether to keep `/` as the path.
   * @return normalized path.
   */
  private static String normalize(String path, boolean keepSingleSlash) {
    int indexOfLastSlash = path.length() - 1;
    while (indexOfLastSlash >= 0) {
      if (path.charAt(indexOfLastSlash) != SEPARATOR_CHAR) {
        break;
      }
      indexOfLastSlash--;
    }
    indexOfLastSlash++;
    if (indexOfLastSlash == path.length()) {
      return path;
    }
    if (keepSingleSlash && indexOfLastSlash == 0) {
      // All slashes and we want to keep one slash
      return SEPARATOR;
    }
    return path.substring(0, indexOfLastSlash);
  }
}

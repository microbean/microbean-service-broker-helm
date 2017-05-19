/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2017 MicroBean.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.servicebroker.helm;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class Helm {

  private static final String LS = System.getProperty("line.separator", "\n");

  private static DateTimeFormatter HELM_TIME_FORMATTER = DateTimeFormatter.ofPattern("E MMM ppd HH:mm:ss yyyy");

  private final Logger logger;
  
  private final File temporaryDirectory;
  
  private final String helmCommand;

  public Helm() {
    this(null);
  }
  
  public Helm(String helmCommand) {
    super();
    this.logger = LoggerFactory.getLogger(this.getClass());
    assert this.logger != null;
    if (helmCommand == null) {
      helmCommand = System.getProperty("helm.command", System.getenv("HELM_COMMAND"));
      if (helmCommand == null) {
        helmCommand = "/usr/local/bin/helm";
      }
    }
    assert helmCommand != null;
    this.temporaryDirectory = new File(System.getProperty("java.io.tmpdir"));
    this.helmCommand = helmCommand;
  }

  public void delete(final String releaseName,
                     final boolean purge)
    throws HelmException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}, {}", releaseName, purge);
    }
    Objects.requireNonNull(releaseName);
    final List<String> command = new ArrayList<>();
    command.add(this.helmCommand);
    command.add("delete");
    command.add(releaseName);
    if (purge) {
      command.add("--purge");
    }
    Process deleteProcess = null;
    try {
      deleteProcess = runAndCheckForErrors(command);
    } catch (final HelmException helmException) {
      final Throwable cause = helmException.getCause();
      if (cause == null) {
        final String message = helmException.getMessage();
        if (message != null && message.startsWith("Error: release: \"" + releaseName + "\" not found")) {
          throw new NoSuchReleaseException(releaseName);
        }
      }
      throw helmException;
    }
    assert deleteProcess != null;
    assert !deleteProcess.isAlive();
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT");
    }
  }

  
  public Status install(final String chartName,
                        final String releaseName,
                        final String releaseTemplateName,
                        final String namespace,
                        final boolean noHooks,
                        final boolean replace,
                        final Collection<? extends Path> valueFiles,
                        final boolean verify,
                        final String version)
    throws HelmException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}, {}, {}, {}, {}, {}, {}, {}, {}", chartName, releaseName, releaseTemplateName, namespace, noHooks, replace, valueFiles, verify, version);
    }
    Objects.requireNonNull(chartName);
    final List<String> command = new ArrayList<>();
    command.add(this.helmCommand);
    command.add("install");
    command.add(chartName);
    if (releaseName != null) {
      command.add("--name");
      command.add(releaseName);
    }
    if (releaseTemplateName != null) {
      command.add("--name-template");
      command.add(releaseTemplateName);
    }
    if (namespace != null) {
      command.add("--namespace");
      command.add(namespace);      
    }
    if (noHooks) {
      command.add("--no-hooks");
    }
    if (replace) {
      command.add("--replace");
    }
    if (valueFiles != null && !valueFiles.isEmpty()) {
      command.add("--values");
      for (final Path valuesFile : valueFiles) {
        if (valuesFile != null) {
          command.add(valuesFile.toAbsolutePath().toString());
        }
      }
    }
    if (verify) {
      command.add("--verify");
    }
    if (version != null) {
      command.add("--version");
      command.add(version);
    }
    Process installProcess = null;
    Exception thrownException = null;
    try {
      installProcess = runAndCheckForErrors(command);
    } catch (final RuntimeException runtimeException) {
      thrownException = runtimeException;
      throw runtimeException;
    } catch (HelmException helmException) {
      final Throwable cause = helmException.getCause();
      if (cause == null) {
        final String message = helmException.getMessage();
        if (message != null && message.contains("Error: a release named \"" + releaseName + "\" already exists")) {
          helmException = new DuplicateReleaseException(releaseName);
        }
      }
      thrownException = helmException;
      throw helmException;
    } finally {
      if (valueFiles != null && !valueFiles.isEmpty()) {
        for (final Path valuesFile : valueFiles) {
          if (valuesFile != null) {
            try {
              Files.deleteIfExists(valuesFile);
            } catch (final IOException ioException) {
              if (thrownException == null) {
                // Hmm; not sure.  For now, just ignore the failed deletion attempt.
              } else {
                thrownException.addSuppressed(ioException);
              }
            }
          }
        }
      }
    }
    Status returnValue = null;
    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(installProcess.getInputStream()))) {
      assert reader != null;
      returnValue = parseStatus(getOutput(reader));
    } catch (final IOException ioException) {
      throw new HelmException(ioException);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  public Status upgrade(final String releaseName,
                        final String chartName,
                        final boolean install,
                        final String installationNamespace,
                        final String version)
    throws HelmException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}, {}, {}, {}, {}", releaseName, chartName, install, installationNamespace, version);
    }
    Objects.requireNonNull(releaseName);
    Objects.requireNonNull(chartName);
    final List<String> command = new ArrayList<>();
    command.add(this.helmCommand);
    command.add("upgrade");
    command.add(releaseName);
    command.add(chartName);
    if (install) {
      command.add("--install");
      if (installationNamespace != null) {
        command.add("--namespace");
        command.add(installationNamespace);      
      }
    }
    if (version != null) {
      command.add("--version");
      command.add(version);
    }
    final Process upgradeProcess = runAndCheckForErrors(command);
    assert upgradeProcess != null;
    Status returnValue = null;
    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(upgradeProcess.getInputStream()))) {
      assert reader != null;
      returnValue = parseStatus(getOutput(reader));
    } catch (final IOException ioException) {
      throw new HelmException(ioException);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  public boolean isDeployed(final String filterRegexOperand) throws HelmException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", filterRegexOperand);
    }
    Objects.requireNonNull(filterRegexOperand);
    final Collection<String> items = this.list(false,
                                               true, // byDate
                                               false,
                                               false,
                                               false,
                                               false,
                                               1, // max
                                               null,
                                               true, // reverse
                                               true, // quiet
                                               filterRegexOperand);
    final boolean returnValue =  items != null && !items.isEmpty();
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  public List<String> list(final boolean all,
                           final boolean byDate,
                           final boolean deleted,
                           final boolean deleting,
                           final boolean deployed,
                           final boolean failed,
                           final Integer max,
                           final String offsetReleaseName,
                           final boolean reverse,
                           final boolean quiet,
                           final String filterRegexOperand)
    throws HelmException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}", all, byDate, deleted, deleting, deployed, failed, max, offsetReleaseName, reverse, quiet, filterRegexOperand);
    }
    final List<String> command = new ArrayList<>();
    command.add(this.helmCommand);
    command.add("list");
    if (all) {
      command.add("--all");
    }
    if (byDate) {
      command.add("--date");
    }
    if (deleted) {
      command.add("--deleted");
    }
    if (deleting) {
      command.add("--deleting");
    }
    if (deployed) {
      command.add("--deployed");
    }
    if (failed) {
      command.add("--failed");
    }
    if (max != null) {
      command.add("--max");
      command.add(max.toString());
    }
    if (offsetReleaseName != null) {
      command.add("--offset");
      command.add(offsetReleaseName);
    }
    if (reverse) {
      command.add("--reverse");
    }
    if (quiet) {
      command.add("--short");
    }
    if (filterRegexOperand != null) {
      command.add(filterRegexOperand);
    }
    final Process listProcess = runAndCheckForErrors(command);
    assert listProcess != null;
    List<String> returnValue = null;
    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()))) {
      assert reader != null;
      returnValue = getOutput(reader);
    } catch (final IOException ioException) {
      throw new HelmException(ioException);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }
  
  public Set<String> search() throws HelmException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY");
    }
    final List<String> command = new ArrayList<>();
    command.add(this.helmCommand);
    command.add("search");
    final Process searchProcess = runAndCheckForErrors(command);
    assert searchProcess != null;
    final Set<String> returnValue = new LinkedHashSet<>();
    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(searchProcess.getInputStream()))) {
      assert reader != null;      
      String line = null;
      while ((line = reader.readLine()) != null) {
        final int spaceIndex = line.indexOf(" ");
        assert spaceIndex > 0;
        returnValue.add(line.substring(0, spaceIndex));
      }
    } catch (final IOException ioException) {
      throw new HelmException(ioException);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return Collections.unmodifiableSet(returnValue);
  }

  public Status status(final String releaseName) throws HelmException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", releaseName);
    }
    Objects.requireNonNull(releaseName);
    final List<String> command = new ArrayList<>();
    command.add(this.helmCommand);
    command.add("status");
    command.add(releaseName);
    Process statusProcess = null;
    try {
      statusProcess = runAndCheckForErrors(command);
    } catch (final HelmException helmException) {
      final Throwable cause = helmException.getCause();
      if (cause == null) {
        final String message = helmException.getMessage();
        if (message != null && message.startsWith("Error: getting deployed release \"" + releaseName + "\": release: \"" + releaseName + "\" not found")) {
          throw new NoSuchReleaseException(releaseName);
        }
      }
      throw helmException;
    }
    assert statusProcess != null;
    Status returnValue = null;
    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(statusProcess.getInputStream()))) {
      assert reader != null;
      returnValue = parseStatus(getOutput(reader));
    } catch (final IOException ioException) {
      throw new HelmException(ioException);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  private static final List<String> getOutput(final BufferedReader reader) throws IOException {
    final Logger logger = LoggerFactory.getLogger(Helm.class);
    assert logger != null;
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", reader);
    }
    final List<String> returnValue = new ArrayList<>();
    if (reader != null) {
      String line = null;
      while ((line = reader.readLine()) != null) {
        returnValue.add(line);
      }
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  private final Process runAndCheckForErrors(final List<String> command) throws HelmException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", command);
    }
    Objects.requireNonNull(command);
    if (command.isEmpty()) {
      throw new IllegalArgumentException("command.isEmpty()");
    }
    if (!this.helmCommand.equals(command.get(0))) {
      throw new IllegalArgumentException("Not an invocation of helm: " + command);
    }
    final ProcessBuilder builder = new ProcessBuilder(command).directory(this.temporaryDirectory);
    Process process = null;
    try {
      process = builder.start();
    } catch (final IOException ioException) {
      throw new HelmException(ioException);
    }
      
    assert process != null;
    try {
      process.waitFor();
    } catch (final InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new HelmException(interruptedException);
    }
    
    final int exitCode = process.exitValue();
    if (exitCode != 0) {
      final StringBuilder errorMessage = new StringBuilder();
      try (final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
        String line = null;
        while ((line = reader.readLine()) != null) {
          errorMessage.append(line).append(LS);
        }        
      } catch (final IOException ioException) {
        throw new HelmException(errorMessage.toString(), ioException);
      }
      throw new HelmException(errorMessage.toString());
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", process);
    }
    return process;
  }

  private static final int EXPECTING_LAST_DEPLOYED_OR_NAMESPACE = 0;

  private static final int EXPECTING_LAST_DEPLOYED = 1;

  private static final int EXPECTING_NAMESPACE = 2;

  private static final int EXPECTING_STATUS = 3;

  private static final int EXPECTING_RESOURCES_HEADER_OR_TEST_SUITE_HEADER_OR_NOTES_HEADER = 4;

  private static final int EXPECTING_RESOURCES_HEADER = 5;

  private static final int EXPECTING_RESOURCE_TYPE = 6;
  
  private static final int EXPECTING_RESOURCE_KEYS = 7;

  private static final int EXPECTING_RESOURCE_VALUES = 8;

  private static final int EXPECTING_RESOURCE_VALUES_OR_RESOURCE_TYPE_OR_TEST_SUITE_HEADER_OR_NOTES_HEADER = 9;
  
  private static final int EXPECTING_TEST_SUITE_HEADER = 10;

  private static final int EXPECTING_TEST_SUITE_VALUES = 11;

  private static final int EXPECTING_TEST_SUITE_VALUES_OR_NOTES_HEADER = 12;

  private static final int EXPECTING_NOTES_HEADER = 13;

  private static final int EXPECTING_NOTES = 14;

  static final Status parseStatus(final List<String> lines) {
    final Logger logger = LoggerFactory.getLogger(Helm.class);
    assert logger != null;
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", lines);
    }
    
    /*
     * Helm status is very brittle.  See
     * https://github.com/kubernetes/helm/blob/v2.3.1/cmd/helm/status.go#L93-L118
     * for the version understood by this method.
     */

    Status status = null;
    if (lines != null && !lines.isEmpty()) {
      Map<String, Map<String, Map<String, String>>> resourceMapsByType = null;
      Map<String, Map<String, String>> resourceMapsByName = null;
      final LinkedHashMap<String, Object> resourceMap = new LinkedHashMap<>();
      final List<String> notes = new ArrayList<>();
      String resourceType = null;
      LinkedHashMap<String, String> resourceDetails = null;
      status = new Status();
      int state = EXPECTING_LAST_DEPLOYED_OR_NAMESPACE;
      final int linesSize = lines.size();
      int lineIndex = 0;
      while (lineIndex < linesSize) {
        String line = lines.get(lineIndex++);
        if (line != null) {
          if (state != EXPECTING_NOTES) {
            line = line.trim();
          }
          if (state == EXPECTING_NOTES || !line.isEmpty()) { // state == EXPECTING_NOTES: notes can get blank lines; no other state can
            switch (state) {
              
            case EXPECTING_LAST_DEPLOYED_OR_NAMESPACE:
              if (line.startsWith("LAST DEPLOYED:") && line.length() > "LAST DEPLOYED:".length()) {
                state = EXPECTING_LAST_DEPLOYED;
                lineIndex--;
              } else if (line.startsWith("NAMESPACE:") && line.length() > "NAMESPACE:".length()) {
                state = EXPECTING_NAMESPACE;
                lineIndex--;
              } else {
                // This is the initial state, so we're going to be
                // really lenient here and skip leading lines of any
                // kind.  This is because helm upgrade, for example,
                // spits out status underneath some prefix lines.
              }
              break;

            case EXPECTING_LAST_DEPLOYED:
              if (line.startsWith("LAST DEPLOYED:") && line.length() > "LAST DEPLOYED:".length()) {
                // Helm currently outputs the LAST DEPLOYED time
                // without a time zone in a sort-of ANSI C format in
                // the client's time zone. Sigh.
                final LocalDateTime localDeploymentTime = LocalDateTime.parse(line.substring("LAST DEPLOYED:".length()).trim(), HELM_TIME_FORMATTER);
                assert localDeploymentTime != null;
                final ZonedDateTime zonedDateTime = localDeploymentTime.atZone(ZoneId.systemDefault());
                assert zonedDateTime != null;
                final Instant deploymentInstant = zonedDateTime.toInstant();
                assert deploymentInstant != null;
                status.setLastDeployed(deploymentInstant);
              } else {
                throw new IllegalStateException();
              }
              state = EXPECTING_NAMESPACE;
              break;

            case EXPECTING_NAMESPACE:
              if (line.startsWith("NAMESPACE:") && line.length() > "NAMESPACE:".length()) {
                status.setNamespace(line.substring("NAMESPACE:".length()).trim());
              } else {
                throw new IllegalStateException();
              }
              state = EXPECTING_STATUS;
              break;

            case EXPECTING_STATUS:
              if (line.startsWith("STATUS:") && line.length() > "STATUS:".length()) {
                status.setStatus(line.substring("STATUS:".length()).trim());
              } else {
                throw new IllegalStateException();
              }
              state = EXPECTING_RESOURCES_HEADER_OR_TEST_SUITE_HEADER_OR_NOTES_HEADER;
              break;

            case EXPECTING_RESOURCES_HEADER_OR_TEST_SUITE_HEADER_OR_NOTES_HEADER:
              switch (line) {
              case "RESOURCES:":
                state = EXPECTING_RESOURCES_HEADER;
                break;
              case "TEST SUITE:":
                state = EXPECTING_TEST_SUITE_HEADER;
                break;
              case "NOTES:":
                state = EXPECTING_NOTES_HEADER;
                break;
              default:
                throw new IllegalStateException();
              }
              lineIndex--;
              break;
              
            case EXPECTING_RESOURCES_HEADER:
              if (line.equals("RESOURCES:")) {
                resourceType = null;
                resourceMapsByType = new HashMap<>(11);
                resourceMapsByName = new HashMap<>(11);
                assert resourceMap.isEmpty();
              } else {
                throw new IllegalStateException();
              }
              state = EXPECTING_RESOURCE_TYPE;
              break;

            case EXPECTING_RESOURCE_TYPE:
              if (line.startsWith("==>") && line.length() > "==>".length()) {
                resourceType = line.substring("==>".length()).trim();
                if (resourceType.isEmpty()) {
                  throw new IllegalStateException();
                }
                assert resourceMapsByType != null;
                resourceMapsByName = new HashMap<>(11);
                resourceMapsByType.put(resourceType, resourceMapsByName);
                resourceMap.clear();
              } else {
                throw new IllegalStateException();
              }
              state = EXPECTING_RESOURCE_KEYS;
              break;

            case EXPECTING_RESOURCE_KEYS:
              if (line.startsWith("NAME") && line.length() > "NAME".length()) {
                assert resourceMap != null;
                assert resourceMap.isEmpty();
                final char[] lineChars = line.toCharArray();
                assert lineChars != null;
                final int length = lineChars.length;
                assert length == line.length();
                int c = -1;
                Integer index = Integer.valueOf(0);
                final StringBuilder keyBuffer = new StringBuilder("NAME");
                for (int i = 4; i < length; i++) { // 4 == position right after "NAME"
                  c = lineChars[i];
                  if (Character.isWhitespace(c)) {
                    if (index != null) {
                      resourceMap.put(keyBuffer.toString(), index);
                      keyBuffer.setLength(0);
                      index = null;
                    }
                  } else {
                    if (index == null) {
                      index = Integer.valueOf(i);
                    }
                    keyBuffer.append((char)c);
                  }
                }
                if (keyBuffer.length() > 0 && index != null) {
                  resourceMap.put(keyBuffer.toString(), index);
                  keyBuffer.setLength(0);
                  index = null;
                }
              } else {
                throw new IllegalStateException();
              }
              state = EXPECTING_RESOURCE_VALUES;
              break;

            case EXPECTING_RESOURCE_VALUES_OR_RESOURCE_TYPE_OR_TEST_SUITE_HEADER_OR_NOTES_HEADER:
              boolean allDoneWithAllResources = false;
              if (line.startsWith("==>") && line.length() > "==>".length()) {
                state = EXPECTING_RESOURCE_TYPE;
              } else if (line.equals("NOTES:")) {
                allDoneWithAllResources = true;
                state = EXPECTING_NOTES_HEADER;
              } else if (line.equals("TEST SUITE:")) {
                allDoneWithAllResources = true;
                state = EXPECTING_TEST_SUITE_HEADER;
              } else {
                state = EXPECTING_RESOURCE_VALUES;
              }
              if (allDoneWithAllResources) {
                status.setResources(resourceMapsByType);
                resourceType = null;
                resourceMapsByType = null;
                resourceMapsByName = null;
              }
              lineIndex--;
              break;

            case EXPECTING_RESOURCE_VALUES:
              assert resourceType != null;
              assert resourceMapsByName != null;
              assert resourceMap != null;
              assert resourceMap.containsKey("NAME");
              assert resourceMap.get("NAME") instanceof Integer;

              final LinkedHashMap<String, String> clone = new LinkedHashMap<>();
              assert clone != null;
              
              final Set<Entry<String, Object>> resourceMapEntries = resourceMap.entrySet();
              assert resourceMapEntries != null;
              assert !resourceMapEntries.isEmpty();
              for (final Entry<String, Object> entry : resourceMapEntries) {
                assert entry != null;
                final String key = entry.getKey();
                assert key != null;
                
                final Object rawValue = entry.getValue();
                assert rawValue instanceof Integer;
                final int startIndex = ((Integer)rawValue).intValue();

                final String chunk = line.substring(startIndex);
                int whitespaceIndex = chunk.indexOf(' ');
                if (whitespaceIndex < 0) {
                  whitespaceIndex = chunk.indexOf('\t');
                }

                final String value;
                if (whitespaceIndex < 0) {
                  value = chunk;
                } else if (whitespaceIndex == 0) {
                  value = null;
                } else {
                  assert whitespaceIndex > 0;
                  value = chunk.substring(0, whitespaceIndex);
                }

                if (value == null || value.equals("<none>")) {
                  clone.put(key, null);
                } else {
                  clone.put(key, value);
                }
              }
              assert clone.get("NAME") != null;
              resourceMapsByName.put(clone.get("NAME"), clone);

              assert resourceMap.containsKey("NAME");
              assert resourceMap.get("NAME") instanceof Integer;
              state = EXPECTING_RESOURCE_VALUES_OR_RESOURCE_TYPE_OR_TEST_SUITE_HEADER_OR_NOTES_HEADER;
              break;

            case EXPECTING_TEST_SUITE_HEADER:
              if (!line.equals("TEST SUITE:")) {
                throw new IllegalStateException();
              }
              state = EXPECTING_TEST_SUITE_VALUES;
              break;

            case EXPECTING_TEST_SUITE_VALUES_OR_NOTES_HEADER:
              if (line.equals("NOTES:")) {
                state = EXPECTING_NOTES_HEADER;
                lineIndex--;
              } else {
                state = EXPECTING_TEST_SUITE_VALUES;
                lineIndex--;
              }
              break;
              
            case EXPECTING_TEST_SUITE_VALUES:
              if (true) {
                // skip them for now
              }
              state = EXPECTING_TEST_SUITE_VALUES_OR_NOTES_HEADER;
              break;

            case EXPECTING_NOTES_HEADER:
              if (!line.equals("NOTES:")) {
                throw new IllegalStateException();
              }
              state = EXPECTING_NOTES;
              break;
              
            case EXPECTING_NOTES:
              notes.add(line);
              break;
              
            default:
              throw new IllegalStateException("State: " + state);
            }
          }
        }
      }
      assert resourceMapsByType == null;
      if (notes != null && !notes.isEmpty()) {
        status.setNotes(notes);
      }
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", status);
    }
    return status;
  }
  
  public static final class Status {

    private Instant lastDeployed;
    
    private String namespace;

    private String status;

    private Map<String, Map<String, Map<String, String>>> resources;

    private List<String> notes;
    
    private Status() {
      super();
    }

    public final List<String> getNotes() {
      return this.notes;
    }

    private final void setNotes(final List<String> notes) {
      this.notes = notes;
    }

    public Map<String, Map<String, Map<String, String>>> getResources() {
      return this.resources;
    }

    private final void setResources(final Map<String, Map<String, Map<String, String>>> resources) {
      this.resources = resources;
    }

    // TODO: use Instant instead
    public final Instant getLastDeployed() {
      return this.lastDeployed;
    }
    
    private final void setLastDeployed(final Instant instant) {
      this.lastDeployed = instant;
    }

    public final String getNamespace() {
      return this.namespace;
    }
    
    private final void setNamespace(final String namespace) {
      this.namespace = namespace;
    }

    public final String getStatus() {
      return this.status;
    }
    
    private final void setStatus(final String status) {
      this.status = status;
    }
    
  }
  
}

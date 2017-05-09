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

import java.io.IOException;

import java.net.URISyntaxException;

import java.nio.file.Files;
import java.nio.file.Paths;

import java.util.Collection;
import java.util.List;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class TestParseStatus {

  public TestParseStatus() {
    super();
  }

  @Test
  public void testParseValidStatus() throws IOException, URISyntaxException {
    final List<String> output = Files.readAllLines(Paths.get(Thread.currentThread().getContextClassLoader().getResource("sample-output.txt").toURI()));
    assertNotNull(output);
    assertFalse(output.isEmpty());
    final Helm.Status status = Helm.parseStatus(output);
    assertNotNull(status);
    System.out.println(status.getLastDeployed());
    System.out.println(status.getResources());
    System.out.println(status.getNotes());
  }

  @Test
  public void testProcessNotes() throws HelmException, IOException, URISyntaxException {
    final List<String> output = Files.readAllLines(Paths.get(Thread.currentThread().getContextClassLoader().getResource("sample-output.txt").toURI()));
    assertNotNull(output);
    assertFalse(output.isEmpty());
    final Helm.Status status = Helm.parseStatus(output);
    assertNotNull(status);
    final List<String> notes = status.getNotes();
    assertNotNull(notes);
  }
  
}

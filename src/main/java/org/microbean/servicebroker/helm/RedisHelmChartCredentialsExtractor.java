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

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.enterprise.context.ApplicationScoped;

import javax.inject.Inject;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import io.fabric8.kubernetes.api.model.Secret;

import org.microbean.servicebroker.api.ServiceBrokerException;

import org.microbean.servicebroker.helm.annotation.Chart;

@ApplicationScoped
@Chart("redis")
public class RedisHelmChartCredentialsExtractor implements CredentialsExtractor {

  private static final Pattern HOSTNAME_PATTERN = Pattern.compile("\\S+\\.svc\\.cluster\\.local$");

  private static final Pattern KUBECTL_GET_SECRET_PATTERN = Pattern.compile("kubectl get secret --namespace (\\S+) (\\S+) -o jsonpath=\"\\{\\.data\\.([^}]+)\\}\" | base64 --decode\\)$");
  
  private final KubernetesClient kubernetesClient;

  @Inject
  public RedisHelmChartCredentialsExtractor(final KubernetesClient kubernetesClient) {
    super();
    this.kubernetesClient = kubernetesClient;
  }

  @Override
  public Map<? extends String, ?> extractCredentials(final Helm.Status status) throws ServiceBrokerException {
    Map<String, String> credentials = null;
    if (status != null) {
      final List<String> notes = status.getNotes();
      if (notes != null && !notes.isEmpty()) {
        boolean foundHost = false;
        boolean foundPassword = false;
        credentials = new HashMap<>();
        credentials.put("port", "6379"); // the chart hardcodes this
        for (String line : notes) {
          if (line != null) {
            Matcher matcher = null;
            if (!foundHost) {
              matcher = HOSTNAME_PATTERN.matcher(line);
              assert matcher != null;
              if (matcher.find()) {
                final String host = matcher.group(0);
                foundHost = true;
                credentials.put("host", host);
                credentials.put("hostname", host);
              }
            }
            if (!foundPassword) {
              matcher = KUBECTL_GET_SECRET_PATTERN.matcher(line);
              assert matcher != null;
              if (matcher.find()) {
                final String namespace = matcher.group(1);
                final String secretName = matcher.group(2);
                final String secretKey = matcher.group(3);
                byte[] decodedSecretValue = null;
                try {
                  decodedSecretValue = this.getDecodedSecretValue(namespace, secretName, secretKey);
                } catch (final KubernetesClientException kaboom) {
                  throw new ServiceBrokerException(kaboom);
                }
                foundPassword = true;
                if (decodedSecretValue != null) {
                  credentials.put(secretKey, new String(decodedSecretValue)); // yes, using the platform default CharSet
                }
              }
            }
          }
        }
      }
    }
    return credentials;
  }

  protected byte[] getDecodedSecretValue(String namespace, final String secretName, final String key) {
    if (namespace == null) {
      namespace = "default";
    }
    byte[] returnValue = null;
    if (this.kubernetesClient != null && secretName != null && key != null) {
      final String base64EncodedValue = this.kubernetesClient.secrets().inNamespace(namespace).withName(secretName).get().getData().get(key);
      if (base64EncodedValue != null) {
        returnValue = Base64.getDecoder().decode(base64EncodedValue);
      }
    }
    return returnValue;
  }
  
}

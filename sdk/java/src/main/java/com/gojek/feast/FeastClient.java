/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2019 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gojek.feast;

import feast.proto.serving.ServingAPIProto.FeatureReferenceV2;
import feast.proto.serving.ServingAPIProto.GetFeastServingInfoRequest;
import feast.proto.serving.ServingAPIProto.GetFeastServingInfoResponse;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesRequestV2;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesResponse;
import feast.proto.serving.ServingServiceGrpc;
import feast.proto.serving.ServingServiceGrpc.ServingServiceBlockingStub;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("WeakerAccess")
public class FeastClient extends GrpcManager<ServingServiceBlockingStub> {
  Logger logger = LoggerFactory.getLogger(FeastClient.class);

  /**
   * Create a client to access Feast Serving.
   *
   * @param host hostname or ip address of Feast serving GRPC server
   * @param port port number of Feast serving GRPC server
   * @return {@link FeastClient}
   */
  public static FeastClient create(String host, int port) {
    // configure client with no security config.
    return FeastClient.createSecure(host, port, SecurityConfig.newBuilder().build());
  }

  /**
   * Create a authenticated client that can access Feast serving with authentication enabled.
   * Supports the {@link CallCredentials} in the {@link feast.common.auth.credentials} package.
   *
   * @param host hostname or ip address of Feast serving GRPC server
   * @param port port number of Feast serving GRPC server
   * @param securityConfig security options to configure the Feast client. See {@link
   *     SecurityConfig} for options.
   * @return {@link FeastClient}
   */
  public static FeastClient createSecure(String host, int port, SecurityConfig securityConfig) {
    return new FeastClient(host, port, securityConfig);
  }

  /**
   * Obtain info about Feast Serving.
   *
   * @return {@link GetFeastServingInfoResponse} containing Feast version, Serving type etc.
   */
  public GetFeastServingInfoResponse getFeastServingInfo() {
    return stub.getFeastServingInfo(GetFeastServingInfoRequest.newBuilder().build());
  }

  /**
   * Get online features from Feast, without indicating project, will use `default`.
   *
   * <p>See {@link #getOnlineFeatures(List, List, String)}
   *
   * @param featureRefs list of string feature references to retrieve in the following format
   *     featureTable:feature, where 'featureTable' and 'feature' refer to the FeatureTable and
   *     Feature names respectively. Only the Feature name is required.
   * @param rows list of {@link Row} to select the entities to retrieve the features for.
   * @return list of {@link Row} containing retrieved data fields.
   */
  public List<Row> getOnlineFeatures(List<String> featureRefs, List<Row> rows) {
    return getOnlineFeatures(featureRefs, rows, "");
  }

  /**
   * Get online features from Feast.
   *
   * <p>Example of retrieving online features for the driver FeatureTable, with features driver_id
   * and driver_name
   *
   * <pre>{@code
   * FeastClient client = FeastClient.create("localhost", 6566);
   * List<String> requestedFeatureIds = Arrays.asList("driver:driver_id", "driver:driver_name");
   * List<Row> requestedRows =
   *         Arrays.asList(Row.create().set("driver_id", 123), Row.create().set("driver_id", 456));
   * List<Row> retrievedFeatures = client.getOnlineFeatures(requestedFeatureIds, requestedRows);
   * retrievedFeatures.forEach(System.out::println);
   * }</pre>
   *
   * @param featureRefs list of string feature references to retrieve in the following format
   *     featureTable:feature, where 'featureTable' and 'feature' refer to the FeatureTable and
   *     Feature names respectively. Only the Feature name is required.
   * @param rows list of {@link Row} to select the entities to retrieve the features for
   * @param project {@link String} Specifies the project override. If specified uses the project for
   *     retrieval. Overrides the projects set in Feature References if also specified.
   * @return list of {@link Row} containing retrieved data fields.
   */
  public List<Row> getOnlineFeatures(List<String> featureRefs, List<Row> rows, String project) {
    List<FeatureReferenceV2> features = RequestUtil.createFeatureRefs(featureRefs);
    // build entity rows and collect entity references
    HashSet<String> entityRefs = new HashSet<>();
    List<EntityRow> entityRows =
        rows.stream()
            .map(
                row -> {
                  entityRefs.addAll(row.getFields().keySet());
                  return EntityRow.newBuilder()
                      .setTimestamp(row.getEntityTimestamp())
                      .putAllFields(row.getFields())
                      .build();
                })
            .collect(Collectors.toList());

    GetOnlineFeaturesResponse response =
        stub.getOnlineFeaturesV2(
            GetOnlineFeaturesRequestV2.newBuilder()
                .addAllFeatures(features)
                .addAllEntityRows(entityRows)
                .setProject(project)
                .build());

    return response.getFieldValuesList().stream()
        .map(
            fieldValues -> {
              Row row = Row.create();
              for (String fieldName : fieldValues.getFieldsMap().keySet()) {
                row.set(
                    fieldName,
                    fieldValues.getFieldsMap().get(fieldName),
                    fieldValues.getStatusesMap().get(fieldName));
              }
              return row;
            })
        .collect(Collectors.toList());
  }

  @Override
  protected ServingServiceBlockingStub getStub(Channel channel) {
    return ServingServiceGrpc.newBlockingStub(channel);
  }

  protected FeastClient(ManagedChannel channel, Optional<CallCredentials> credentials) {
    super(channel, credentials);
  }

  protected FeastClient(String host, int port, SecurityConfig securityConfig) {
    super(host, port, securityConfig);
  }
}

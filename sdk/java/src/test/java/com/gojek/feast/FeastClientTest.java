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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;

import com.google.protobuf.Timestamp;
import feast.common.auth.credentials.JwtCallCredentials;
import feast.proto.serving.ServingAPIProto.FeatureReferenceV2;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesRequestV2;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesResponse;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesResponse.FieldValues;
import feast.proto.serving.ServingServiceGrpc.ServingServiceImplBase;
import feast.proto.types.ValueProto.Value;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class FeastClientTest {
  protected final String AUTH_TOKEN = "test token";
  private GrpcMock grpcMock;

  private ServingServiceImplBase servingMock =
      mock(
          ServingServiceImplBase.class,
          delegatesTo(
              new ServingServiceImplBase() {
                @Override
                public void getOnlineFeaturesV2(
                    GetOnlineFeaturesRequestV2 request,
                    StreamObserver<GetOnlineFeaturesResponse> responseObserver) {
                  if (!request.equals(FeastClientTest.getFakeRequest())) {
                    responseObserver.onError(Status.FAILED_PRECONDITION.asRuntimeException());
                  }

                  responseObserver.onNext(FeastClientTest.getFakeResponse());
                  responseObserver.onCompleted();
                }
              }));

  private FeastClient client;
  private FeastClient authenticatedClient;

  @Before
  public void setup() throws Exception {
    this.grpcMock = new GrpcMock(this.servingMock);
    this.client = new FeastClient(grpcMock.getChannel(), Optional.empty());
    this.authenticatedClient =
        new FeastClient(grpcMock.getChannel(), Optional.of(new JwtCallCredentials(AUTH_TOKEN)));
  }

  @Test
  public void shouldGetOnlineFeatures() {
    shouldGetOnlineFeaturesWithClient(this.client);
  }

  @Test
  public void shouldAuthenticateAndGetOnlineFeatures() {
    grpcMock.resetAuthentication();
    shouldGetOnlineFeaturesWithClient(this.authenticatedClient);
    assertTrue(grpcMock.isAuthenticated());
  }

  private void shouldGetOnlineFeaturesWithClient(FeastClient client) {
    List<Row> rows =
        client.getOnlineFeatures(
            Arrays.asList("driver:name", "driver:rating", "driver:null_value"),
            Arrays.asList(
                Row.create().set("driver_id", 1).setEntityTimestamp(Instant.ofEpochSecond(100))),
            "driver_project");

    assertEquals(
        rows.get(0).getFields(),
        new HashMap<String, Value>() {
          {
            put("driver_id", intValue(1));
            put("driver:name", strValue("david"));
            put("driver:rating", intValue(3));
            put("driver:null_value", Value.newBuilder().build());
          }
        });
    assertEquals(
        rows.get(0).getStatuses(),
        new HashMap<String, FieldStatus>() {
          {
            put("driver_id", FieldStatus.PRESENT);
            put("driver:name", FieldStatus.PRESENT);
            put("driver:rating", FieldStatus.PRESENT);
            put("driver:null_value", FieldStatus.NULL_VALUE);
          }
        });
  }

  private static GetOnlineFeaturesRequestV2 getFakeRequest() {
    // setup mock serving service stub
    return GetOnlineFeaturesRequestV2.newBuilder()
        .addFeatures(
            FeatureReferenceV2.newBuilder().setFeatureTable("driver").setName("name").build())
        .addFeatures(
            FeatureReferenceV2.newBuilder().setFeatureTable("driver").setName("rating").build())
        .addFeatures(
            FeatureReferenceV2.newBuilder().setFeatureTable("driver").setName("null_value").build())
        .addEntityRows(
            EntityRow.newBuilder()
                .setTimestamp(Timestamp.newBuilder().setSeconds(100))
                .putFields("driver_id", intValue(1)))
        .setProject("driver_project")
        .build();
  }

  private static GetOnlineFeaturesResponse getFakeResponse() {
    return GetOnlineFeaturesResponse.newBuilder()
        .addFieldValues(
            FieldValues.newBuilder()
                .putFields("driver_id", intValue(1))
                .putStatuses("driver_id", FieldStatus.PRESENT)
                .putFields("driver:name", strValue("david"))
                .putStatuses("driver:name", FieldStatus.PRESENT)
                .putFields("driver:rating", intValue(3))
                .putStatuses("driver:rating", FieldStatus.PRESENT)
                .putFields("driver:null_value", Value.newBuilder().build())
                .putStatuses("driver:null_value", FieldStatus.NULL_VALUE)
                .build())
        .build();
  }

  private static Value strValue(String val) {
    return Value.newBuilder().setStringVal(val).build();
  }

  private static Value intValue(int val) {
    return Value.newBuilder().setInt32Val(val).build();
  }
}

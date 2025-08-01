// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.http;

import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class TableRowCountActionTest extends StarRocksHttpTestCase {
    private static final String PATH_URI = "/_count";

    @Test
    public void testTableCount() throws IOException {
        Request request = new Request.Builder()
                .get()
                .addHeader("Authorization", rootAuth)
                .url(URI + PATH_URI)
                .build();

        Response response = networkClient.newCall(request).execute();
        JSONObject jsonObject = new JSONObject(response.body().string());
        Assertions.assertEquals(200, jsonObject.getInt("status"));
        Assertions.assertEquals(2000, jsonObject.getLong("size"));
    }
}

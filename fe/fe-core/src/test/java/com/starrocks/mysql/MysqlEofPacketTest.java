// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.mysql;

import com.starrocks.qe.QueryState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

public class MysqlEofPacketTest {
    MysqlCapability capability;

    @BeforeEach
    public void setUp() {
        capability = new MysqlCapability(MysqlCapability.Flag.CLIENT_PROTOCOL_41.getFlagBit());
    }

    @Test
    public void testWrite() {
        MysqlEofPacket packet = new MysqlEofPacket(new QueryState());
        MysqlSerializer serializer = MysqlSerializer.newInstance(capability);

        packet.writeTo(serializer);

        ByteBuffer buffer = serializer.toByteBuffer();

        // assert indicator(int1): 0
        Assertions.assertEquals(0xfe, MysqlCodec.readInt1(buffer));

        // assert warnings(int2): 0
        Assertions.assertEquals(0x00, MysqlCodec.readInt2(buffer));

        // assert status flags(int2): 0
        Assertions.assertEquals(0x00, MysqlCodec.readInt2(buffer));

        Assertions.assertEquals(0, buffer.remaining());
    }
}
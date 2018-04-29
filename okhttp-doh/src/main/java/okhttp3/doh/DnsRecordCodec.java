/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package okhttp3.doh;

import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okio.Buffer;
import okio.ByteString;

public class DnsRecordCodec {
  private static final InetSocketAddress DUMMY =
      InetSocketAddress.createUnresolved("localhost", 53);

  public static String encodeQuery(String host, boolean includeIPv6) {
    Buffer buf = new Buffer();

    buf.writeShort(0); // query id
    buf.writeShort(256); // flags with recursion
    buf.writeShort(includeIPv6 ? 2 : 1); // question count
    buf.writeShort(0); // answerCount
    buf.writeShort(0); // authorityResourceCount
    buf.writeShort(0); // additional

    Buffer nameBuf = new Buffer();
    final String[] labels = host.split("\\.");
    for (String label : labels) {
      nameBuf.writeByte(label.length());
      nameBuf.writeString(label, StandardCharsets.US_ASCII);
    }
    nameBuf.writeByte(0); // end

    nameBuf.copyTo(buf, 0, nameBuf.size());
    buf.writeShort(1); // A
    buf.writeShort(1); // CLASS_IN

    if (includeIPv6) {
      nameBuf.copyTo(buf, 0, nameBuf.size());
      buf.writeShort(0x001c); // AAAA
      buf.writeShort(1); // CLASS_IN
    }

    String encoded = buf.readByteString().base64Url().replace("=", "");

    //System.out.println("Query: " + encoded);

    return encoded;
  }

  public static List<InetAddress> decodeAnswers(String hostname, ByteString byteString)
      throws Exception {
    // TODO check signedness logic

    //System.out.println("Response: " + byteString.hex());

    List<InetAddress> result = new ArrayList<>();

    Buffer buf = new Buffer();
    buf.write(byteString);
    buf.readShort(); // query id

    final int flags = buf.readShort();
    if (flags >> 15 == 0) {
      throw new IllegalArgumentException("not a response");
    }

    byte responseCode = (byte) (flags & 0xf);

    //System.out.println("Code: " + responseCode);
    if (responseCode == DnsResponseCode.NXDOMAIN.intValue()) {
      throw new UnknownHostException(hostname + ": NXDOMAIN");
    } else if (responseCode == DnsResponseCode.SERVFAIL.intValue()) {
      throw new UnknownHostException(hostname + ": SERVFAIL");
    }

    final int questionCount = buf.readShort();
    final int answerCount = buf.readShort();
    buf.readShort(); // authority record count
    buf.readShort(); // additional record count

    for (int i = 0; i < questionCount; i++) {
      consumeName(buf); // name
      buf.readShort(); // type
      buf.readShort(); // class
    }

    for (int i = 0; i < answerCount; i++) {
      consumeName(buf); // name
      buf.skip(1);

      int type = buf.readShort();
      buf.readShort(); // class
      final long ttl = buf.readInt(); // ttl
      final int length = buf.readShort();

      if (type == DnsRecordType.A.intValue() || type == DnsRecordType.AAAA.intValue()) {
        byte[] bytes = new byte[length];
        buf.read(bytes);
        result.add(InetAddress.getByAddress(bytes));
      } else {
        buf.skip(length);
      }
    }

    return result;
  }

  private static void consumeName(Buffer in) throws EOFException {
    // check signedness
    int length = in.readByte();

    while (length > 0) {
      length = in.readByte();
    }
  }
}

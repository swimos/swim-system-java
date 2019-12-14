// Copyright 2015-2019 SWIM.AI inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package swim.mqtt;

import swim.codec.Encoder;
import swim.codec.EncoderException;
import swim.codec.OutputBuffer;

final class MqttPubCompEncoder extends Encoder<Object, MqttPubComp> {

  final MqttEncoder mqtt;
  final MqttPubComp packet;
  final int length;
  final int remaining;
  final int step;

  MqttPubCompEncoder(MqttEncoder mqtt, MqttPubComp packet, int length, int remaining, int step) {
    this.mqtt = mqtt;
    this.packet = packet;
    this.length = length;
    this.remaining = remaining;
    this.step = step;
  }

  MqttPubCompEncoder(MqttEncoder mqtt, MqttPubComp packet) {
    this(mqtt, packet, 0, 0, 1);
  }

  static Encoder<Object, MqttPubComp> encode(OutputBuffer<?> output, MqttEncoder mqtt,
                                             MqttPubComp packet, int length, int remaining, int step) {
    if (step == 1 && output.isCont()) {
      length = packet.bodySize(mqtt);
      remaining = length;
      output = output.write(packet.packetType() << 4 | packet.packetFlags & 0x0f);
      step = 2;
    }
    while (step >= 2 && step <= 5 && output.isCont()) {
      int b = length & 0x7f;
      length = length >>> 7;
      if (length > 0) {
        b |= 0x80;
      }
      output = output.write(b);
      if (length == 0) {
        step = 6;
        break;
      } else if (step < 5) {
        step += 1;
      } else {
        return error(new MqttException("packet length too long: " + remaining));
      }
    }
    if (step == 6 && remaining > 0 && output.isCont()) {
      output = output.write(packet.packetId >>> 8);
      remaining -= 1;
      step = 7;
    }
    if (step == 7 && remaining > 0 && output.isCont()) {
      output = output.write(packet.packetId);
      remaining -= 1;
      step = 8;
    }
    if (step == 8 && remaining == 0) {
      return done(packet);
    }
    if (remaining < 0) {
      return error(new MqttException("packet length too short"));
    } else if (output.isDone()) {
      return error(new EncoderException("truncated"));
    } else if (output.isError()) {
      return error(output.trap());
    }
    return new MqttPubCompEncoder(mqtt, packet, length, remaining, step);
  }

  static Encoder<Object, MqttPubComp> encode(OutputBuffer<?> output, MqttEncoder mqtt,
                                             MqttPubComp packet) {
    return encode(output, mqtt, packet, 0, 0, 1);
  }

  @Override
  public Encoder<Object, MqttPubComp> pull(OutputBuffer<?> output) {
    return encode(output, this.mqtt, this.packet, this.length, this.remaining, this.step);
  }

}

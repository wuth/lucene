/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.analysis.ar;

import static org.apache.lucene.analysis.util.StemmerUtil.delete;

/**
 * Normalizer for Arabic.
 *
 * <p>Normalization is done in-place for efficiency, operating on a termbuffer.
 *
 * <p>Normalization is defined as:
 *
 * <ul>
 *   <li>Normalization of hamza with alef seat to a bare alef.
 *   <li>Normalization of teh marbuta to heh
 *   <li>Normalization of dotless yeh (alef maksura) to yeh.
 *   <li>Removal of Arabic diacritics (the harakat)
 *   <li>Removal of tatweel (stretching character).
 * </ul>
 */
class ArabicNormalizer {
  static final char ALEF = '\u0627';
  static final char ALEF_MADDA = '\u0622';
  static final char ALEF_HAMZA_ABOVE = '\u0623';
  static final char ALEF_HAMZA_BELOW = '\u0625';

  static final char YEH = '\u064A';
  static final char DOTLESS_YEH = '\u0649';

  static final char TEH_MARBUTA = '\u0629';
  static final char HEH = '\u0647';

  static final char TATWEEL = '\u0640';

  static final char FATHATAN = '\u064B';
  static final char DAMMATAN = '\u064C';
  static final char KASRATAN = '\u064D';
  static final char FATHA = '\u064E';
  static final char DAMMA = '\u064F';
  static final char KASRA = '\u0650';
  static final char SHADDA = '\u0651';
  static final char SUKUN = '\u0652';

  /**
   * Normalize an input buffer of Arabic text
   *
   * @param s input buffer
   * @param len length of input buffer
   * @return length of input buffer after normalization
   */
  int normalize(char[] s, int len) {

    for (int i = 0; i < len; i++) {
      switch (s[i]) {
        case ALEF_MADDA:
        case ALEF_HAMZA_ABOVE:
        case ALEF_HAMZA_BELOW:
          s[i] = ALEF;
          break;
        case DOTLESS_YEH:
          s[i] = YEH;
          break;
        case TEH_MARBUTA:
          s[i] = HEH;
          break;
        case TATWEEL:
        case KASRATAN:
        case DAMMATAN:
        case FATHATAN:
        case FATHA:
        case DAMMA:
        case KASRA:
        case SHADDA:
        case SUKUN:
          len = delete(s, i, len);
          i--;
          break;
        default:
          break;
      }
    }

    return len;
  }
}

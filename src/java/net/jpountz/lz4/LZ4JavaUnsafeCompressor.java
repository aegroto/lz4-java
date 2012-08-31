package net.jpountz.lz4;

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

import static net.jpountz.lz4.LZ4UnsafeUtils.hash;
import static net.jpountz.lz4.LZ4UnsafeUtils.hash64k;
import static net.jpountz.lz4.LZ4UnsafeUtils.readIntEquals;
import static net.jpountz.lz4.LZ4UnsafeUtils.wildArraycopy;
import static net.jpountz.lz4.LZ4UnsafeUtils.writeShortLittleEndian;
import static net.jpountz.lz4.LZ4Utils.HASH_TABLE_SIZE;
import static net.jpountz.lz4.LZ4Utils.HASH_TABLE_SIZE_64K;
import static net.jpountz.lz4.LZ4Utils.LAST_LITERALS;
import static net.jpountz.lz4.LZ4Utils.LZ4_64K_LIMIT;
import static net.jpountz.lz4.LZ4Utils.MAX_DISTANCE;
import static net.jpountz.lz4.LZ4Utils.MF_LIMIT;
import static net.jpountz.lz4.LZ4Utils.MIN_LENGTH;
import static net.jpountz.lz4.LZ4Utils.MIN_MATCH;
import static net.jpountz.lz4.LZ4Utils.ML_BITS;
import static net.jpountz.lz4.LZ4Utils.ML_MASK;
import static net.jpountz.lz4.LZ4Utils.RUN_MASK;
import static net.jpountz.lz4.LZ4Utils.SKIP_STRENGTH;
import static net.jpountz.lz4.LZ4Utils.lastLiterals;

import static net.jpountz.util.UnsafeUtils.NATIVE_BYTE_ORDER;
import static net.jpountz.util.UnsafeUtils.readByte;
import static net.jpountz.util.UnsafeUtils.readInt;
import static net.jpountz.util.UnsafeUtils.readLong;
import static net.jpountz.util.UnsafeUtils.readShort;
import static net.jpountz.util.UnsafeUtils.writeByte;
import static net.jpountz.util.UnsafeUtils.writeInt;
import static net.jpountz.util.UnsafeUtils.writeShort;
import static net.jpountz.util.Utils.checkRange;

import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Very fast compressors written in pure Java with the unofficial
 * sun.misc.Unsafe API.
 */
public enum LZ4JavaUnsafeCompressor implements LZ4Compressor {

  FAST {

    public int maxCompressedLength(int length) {
      return LZ4Utils.maxCompressedLength(length);
    }

    private int compress64k(byte[] src, int srcOff, int srcLen, byte[] dest, int destOff, int destEnd) {
      final int srcEnd = srcOff + srcLen;
      final int srcLimit = srcEnd - LAST_LITERALS;
      final int mflimit = srcEnd - MF_LIMIT;

      int sOff = srcOff, dOff = destOff;

      int anchor = sOff;

      if (srcLen > MIN_LENGTH) {

        final short[] hashTable = new short[HASH_TABLE_SIZE_64K];

        ++sOff;

        main:
        while (sOff < srcLimit) {

          // find a match
          int forwardOff = sOff;

          int ref;
          int findMatchAttempts = (1 << SKIP_STRENGTH) + 3;
          while (true) {
            sOff = forwardOff;
            final int step = findMatchAttempts++ >> SKIP_STRENGTH;
            forwardOff += step;

            if (forwardOff > mflimit) {
              break main;
            }

            final int h = hash64k(src, sOff);
            ref = srcOff + readShort(hashTable, h);
            writeShort(hashTable, h, sOff - srcOff);

            if (readIntEquals(src, ref, sOff)) {
              break;
            }
          }

          // catch up
          while (sOff > anchor && ref > srcOff && readByte(src, sOff - 1) == readByte(src, ref - 1)) {
            --sOff;
            --ref;
          }

          // sequence == refsequence
          final int runLen = sOff - anchor;

          // encode literal length
          int tokenOff = dOff++;

          if (dOff + runLen + (2 + 1 + LAST_LITERALS) + (runLen >>> 8) >= destEnd) {
            throw new LZ4Exception("maxDestLen is too small");
          }

          if (runLen >= RUN_MASK) {
            writeByte(dest, tokenOff, RUN_MASK << ML_BITS);
            int len = runLen - RUN_MASK;
            while (len >= 255) {
              writeByte(dest, dOff++, 255);
              len -= 255;
            }
            writeByte(dest, dOff++, len);
          } else {
            writeByte(dest, tokenOff, runLen << ML_BITS);
          }

          // copy literals
          wildArraycopy(src, anchor, dest, dOff, runLen);
          dOff += runLen;

          while (true) {
            // encode offset
            writeShortLittleEndian(dest, dOff, (short) (sOff - ref));
            dOff += 2;

            // count nb matches
            sOff += MIN_MATCH;
            ref += MIN_MATCH;
            int matchLen = 0;
            while (sOff < srcLimit - 8) {
              final long diff = readLong(src, sOff) - readLong(src, ref);
              final int zeroBits;
              if (NATIVE_BYTE_ORDER == ByteOrder.BIG_ENDIAN) {
                zeroBits = Long.numberOfLeadingZeros(diff);
              } else {
                zeroBits = Long.numberOfTrailingZeros(diff);
              }
              if (zeroBits == 64) {
                matchLen += 8;
                sOff += 8;
                ref += 8;
              } else {
                final int inc = zeroBits >>> 3;
                matchLen += inc;
                sOff += inc;
                break;
              }
            }

            // encode match len
            if (matchLen >= ML_MASK) {
              writeByte(dest, tokenOff, readByte(dest, tokenOff) | ML_MASK);
              int len = matchLen - ML_MASK;
              while (len >= 255) {
                writeByte(dest, dOff++, 255);
                len -= 255;
              }
              writeByte(dest, dOff++, len);
            } else {
              writeByte(dest, tokenOff, readByte(dest, tokenOff) | matchLen);
            }

            // test end of chunk
            if (sOff > mflimit) {
              anchor = sOff;
              break main;
            }

            // fill table
            writeShort(hashTable, hash64k(src, sOff - 2), sOff - 2 - srcOff);

            // test next position
            final int h = hash(src, sOff);
            ref = srcOff + readShort(hashTable, h);
            writeShort(hashTable, h, sOff - srcOff);

            if (!readIntEquals(src, sOff, ref)) {
              break;
            }

            tokenOff = dOff++;
            dest[tokenOff] = 0;
          }

          // prepare next loop
          anchor = sOff++;
        }
      }

      dOff = lastLiterals(src, anchor, srcEnd - anchor, dest, dOff, destEnd);
      return dOff - destOff;
    }

    @Override
    public int compress(byte[] src, final int srcOff, int srcLen, byte[] dest, final int destOff, int maxDestLen) {
      checkRange(src, srcOff, srcLen);
      checkRange(dest, destOff, maxDestLen);
      final int destEnd = destOff + maxDestLen;

      if (srcLen < LZ4_64K_LIMIT) {
        return compress64k(src, srcOff, srcLen, dest, destOff, destEnd);
      }

      final int srcEnd = srcOff + srcLen;
      final int srcLimit = srcEnd - LAST_LITERALS;
      final int mflimit = srcEnd - MF_LIMIT;

      int sOff = srcOff, dOff = destOff;
      int anchor = sOff++;
      
      if (srcLen > MIN_LENGTH) {
        final int[] hashTable = new int[HASH_TABLE_SIZE];
        Arrays.fill(hashTable, anchor);

        main:
        while (sOff < srcLimit) {

          // find a match
          int forwardOff = sOff;

          int ref;
          int findMatchAttempts = (1 << SKIP_STRENGTH) + 3;
          int back;
          while (true) {
            sOff = forwardOff;
            final int h = hash(src, sOff);
            final int step = findMatchAttempts++ >> SKIP_STRENGTH;
            forwardOff += step;

            if (forwardOff > mflimit) {
              break main;
            }

            ref = readInt(hashTable, h);
            back = sOff - ref;
            if (back >= MAX_DISTANCE) {
              continue;
            }
            writeInt(hashTable, h, sOff);
            if (readIntEquals(src, ref, sOff)) {
              break;
            }
          }

          // catch up
          while (sOff > anchor && ref > srcOff && readByte(src, sOff - 1) == readByte(src, ref - 1)) {
            --sOff;
            --ref;
          }

          // sequence == refsequence
          final int runLen = sOff - anchor;

          // encode literal length
          int tokenOff = dOff++;

          if (dOff + runLen + (2 + 1 + LAST_LITERALS) + (runLen >>> 8) >= destEnd) {
            throw new LZ4Exception("maxDestLen is too small");
          }

          if (runLen >= RUN_MASK) {
            writeByte(dest, tokenOff, RUN_MASK << ML_BITS);
            int len = runLen - RUN_MASK;
            while (len >= 255) {
              writeByte(dest, dOff++, 255);
              len -= 255;
            }
            writeByte(dest, dOff++, len);
          } else {
            writeByte(dest, tokenOff, runLen << ML_BITS);
          }

          // copy literals
          wildArraycopy(src, anchor, dest, dOff, runLen);
          dOff += runLen;

          while (true) {
            // encode offset
            writeShortLittleEndian(dest, dOff, back);
            dOff += 2;

            // count nb matches
            sOff += MIN_MATCH;
            ref += MIN_MATCH;
            int matchLen = 0;
            while (sOff < srcLimit - 8) {
              final long diff = readLong(src, sOff) - readLong(src, ref);
              final int zeroBits;
              if (NATIVE_BYTE_ORDER == ByteOrder.BIG_ENDIAN) {
                zeroBits = Long.numberOfLeadingZeros(diff);
              } else {
                zeroBits = Long.numberOfTrailingZeros(diff);
              }
              if (zeroBits == 64) {
                matchLen += 8;
                sOff += 8;
                ref += 8;
              } else {
                final int inc = zeroBits >>> 3;
                matchLen += inc;
                sOff += inc;
                break;
              }
            }

            // encode match len
            if (matchLen >= ML_MASK) {
              writeByte(dest, tokenOff, readByte(dest, tokenOff) | ML_MASK);
              int len = matchLen - ML_MASK;
              while (len >= 255) {
                writeByte(dest, dOff++, 255);
                len -= 255;
              }
              writeByte(dest, dOff++, len);
            } else {
              writeByte(dest, tokenOff, readByte(dest, tokenOff) | matchLen);
            }

            // test end of chunk
            if (sOff > mflimit) {
              anchor = sOff;
              break main;
            }

            // fill table
            writeInt(hashTable, hash(src, sOff - 2), sOff - 2);

            // test next position
            final int h = hash(src, sOff);
            ref = readInt(hashTable, h);
            writeInt(hashTable, h, sOff);
            back = sOff - ref;

            if (back >= MAX_DISTANCE || !readIntEquals(src, ref, sOff)) {
              break;
            }

            tokenOff = dOff++;
            writeByte(dest, tokenOff, 0);
          }

          // prepare next loop
          anchor = sOff++;
        }
      }

      dOff = lastLiterals(src, anchor, srcEnd - anchor, dest, dOff, destEnd);
      return dOff - destOff;
    }
  };

}

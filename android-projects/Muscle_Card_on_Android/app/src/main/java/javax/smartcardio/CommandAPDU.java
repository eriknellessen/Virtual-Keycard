/*
 * Copyright 2010 Giesecke & Devrient GmbH.
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

//Former package org.simalliance.openmobileapi.service.security;
//Changed by Erik Nellessen

package javax.smartcardio;

public class CommandAPDU {

    protected int mCla = 0x00;

    protected int mIns = 0x00;

    protected int mP1 = 0x00;

    protected int mP2 = 0x00;

    protected int mLc = 0x00;

    protected byte[] mData = new byte[0];

    protected int mLe = 0x00;

    protected boolean mLeUsed = false;

    //Case 1
    public CommandAPDU(int cla, int ins, int p1, int p2) {
        mCla = cla;
        mIns = ins;
        mP1 = p1;
        mP2 = p2;
    }

    public CommandAPDU() {

    }

    //Case 3
    public CommandAPDU(int cla, int ins, int p1, int p2, byte[] data) {
        mCla = cla;
        mIns = ins;
        mLc = data.length;
        mP1 = p1;
        mP2 = p2;
        mData = data;
    }

    //Case 4
    public CommandAPDU(int cla, int ins, int p1, int p2, byte[] data, int le) {
        mCla = cla;
        mIns = ins;
        mLc = data.length;
        mP1 = p1;
        mP2 = p2;
        mData = data;
        mLe = le;
        mLeUsed = true;
    }

    //Case 2
    public CommandAPDU(int cla, int ins, int p1, int p2, int le) {
        mCla = cla;
        mIns = ins;
        mP1 = p1;
        mP2 = p2;
        mLe = le;
        mLeUsed = true;
    }

    public void setP1(int p1) {
        mP1 = p1;
    }

    public void setP2(int p2) {
        mP2 = p2;
    }

    public void setData(byte[] data) {
        mLc = data.length;
        mData = data;
    }

    public void setLe(int le) {
        mLe = le;
        mLeUsed = true;
    }

    public int getP1() {
        return mP1;
    }

    public int getP2() {
        return mP2;
    }

    public int getLc() {
        return mLc;
    }

    public byte[] getData() {
        return mData;
    }

    public int getLe() {
        return mLe;
    }

    public byte[] toBytes() {
        int length = 4; // CLA, INS, P1, P2
        if (mData.length != 0) {
            length += 1; // LC
            length += mData.length; // DATA
        }
        if (mLeUsed) {
            length += 1; // LE
        }

        byte[] apdu = new byte[length];

        int index = 0;
        apdu[index] = (byte) mCla;
        index++;
        apdu[index] = (byte) mIns;
        index++;
        apdu[index] = (byte) mP1;
        index++;
        apdu[index] = (byte) mP2;
        index++;
        if (mData.length != 0) {
            apdu[index] = (byte) mLc;
            index++;
            System.arraycopy(mData, 0, apdu, index, mData.length);
            index += mData.length;
        }
        if (mLeUsed) {
            apdu[index] += (byte) mLe; // LE
        }

        return apdu;
    }
    
    public byte[] getBytes(){
    	return toBytes();
    }

    public static boolean compareHeaders(byte[] header1, byte[] mask, byte[] header2) {
        if (header1.length < 4 || header2.length < 4) {
            return false;
        }
        byte[] compHeader = new byte[4];
        compHeader[0] = (byte) (header1[0] & mask[0]);
        compHeader[1] = (byte) (header1[1] & mask[1]);
        compHeader[2] = (byte) (header1[2] & mask[2]);
        compHeader[3] = (byte) (header1[3] & mask[3]);

        if (((byte) compHeader[0] == (byte) header2[0])
                && ((byte) compHeader[1] == (byte) header2[1])
                && ((byte) compHeader[2] == (byte) header2[2])
                && ((byte) compHeader[3] == (byte) header2[3])) {
            return true;
        }
        return false;
    }

    public CommandAPDU clone() {
        CommandAPDU apdu = new CommandAPDU();
        apdu.mCla = mCla;
        apdu.mIns = mIns;
        apdu.mP1 = mP1;
        apdu.mP2 = mP2;
        apdu.mLc = mLc;
        apdu.mData = new byte[mData.length];
        System.arraycopy(mData, 0, apdu.mData, 0, mData.length);
        apdu.mLe = mLe;
        apdu.mLeUsed = mLeUsed;
        return apdu;
    }

}

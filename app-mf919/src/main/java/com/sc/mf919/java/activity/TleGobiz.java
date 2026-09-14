package com.sc.mf919.java.activity;

import crypto.Encryption;

import utils.HexUtil;

public class TleGobiz
{
    void xor(byte[] inBuf1, int inBuf1Offset, byte[] inBuf2, int inBuf2Offset, int len, byte[] outXor)
    {
        int i = 0;
        while(len-- > 0)
        {
            byte x = (byte) ((inBuf1[i+inBuf1Offset]) ^ (inBuf2[i+inBuf1Offset]));
            outXor[i] = x;

            i++;
        }
    }

    int generateMac9_19(byte[] datain, int datainlen, byte[] wak16, byte[] outMac8)
    {
        byte[] encMac8 = new byte[8];
        byte[] mac1 = new byte[8];
        int iOffset = 0;
        int iResp = 0;
        //this.sysPrint("datain:", datain, datainlen);
        String encValue;
        while(datainlen>8)
        {
            xor(datain, iOffset, encMac8, iOffset, 8, mac1);
            //es_ecb_encrypt(wak16, mac1, 8, encMac8, &iResp);
            try
            {
                encValue = Encryption.decrypt(HexUtil.bytesToHexString(datain, 0, datainlen), HexUtil.bytesToHexString(wak16), "DESede", "ECB");
            }
            catch (Exception e)
            {
                e.printStackTrace();
                //return null;
            }

            iOffset += 8;
            datainlen -= 8;
        }

        xor(datain, iOffset, encMac8, iOffset,8, mac1);
        //des3_ecb_encrypt(wak16, mac1, 8, encMac8, &iResp);

        Utils.memcpy(outMac8, encMac8, iResp);

        return iResp;
    }

}

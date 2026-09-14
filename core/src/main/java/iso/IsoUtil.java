package iso;

import emv.EmvTag;

import constants.TerminalConstants;

import android.os.Build;

import androidx.annotation.RequiresApi;import utils.HexUtil;
import utils.ByteOps;
import constants.TerminalConstants;
import emv.EmvTag;

public class IsoUtil
{
    EmvTag etag;

    int[] ISO_MSG_DEVAR_LEN;
    int[] ISO_MSG_DEFIX_LEN;
    char[] ISO_MSG_DETYPE;

    private static final String TAG = "ISOU";

    public IsoUtil()
    {
        etag = new EmvTag();
        //                              0   1   2   3   4   5   6   7   8   9   10  11  12  13  14  15  16  17  18  19  20  21  22  23  24  25  26  27  28  29  30  31  32  33  34  35  36  37  38  39  40  41  42  43  44  45  46  47  48  49  50  51  52  53  54  55  56  57  58  59  60  61  62  63  64
        ISO_MSG_DEVAR_LEN = new int[] { 0,  0,  1,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  1,  1,  1,  1,  2,  0,  0,  0,  0,  0,  0,  0,  1,  1,  2,  2,  2,  0,  0,  0,  0,  0,  2,  2,  2,  2,  2,  2,  2,  2,  2,  2,  0   };
        ISO_MSG_DEFIX_LEN = new int[] { 0,  0,  0,  3,  6,  6,  6,  5,  4,  4,  4,  3, 3,   2,  2,  2,  2,  2,  2,  2,  2,  2,  2,  2,  2,  1,  1,  1,  4,  4,  4,  4,  0,  0,  0,  0,  0,  12, 6,  2,  3,  8,  15, 15, 0,  0,  0,  0,  0,  2,  2,  2,  8,  8,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  8   };
        ISO_MSG_DETYPE = new char[]   {'n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','n','a','a','a','a','a','a','a','a','a','a','a','a','n','n','n','n','n','a','a','a','a','a','a','a','a','a','a','n' };

    }
    private static void sysPrint(String log)
    {
        timber.log.Timber.tag(TAG).d(log);
    }

    private static void sysPrint(String message, byte[] data, int dataOffset, int dataLen)
    {
        timber.log.Timber.tag(TAG).d(message + HexUtil.bcd2str(data, dataOffset, dataLen));
    }

    public int splitIntegerText(String sourceText, String separator, int[] outIntegerArray)
    {
        int returnCount = 0;
        String[] sources = sourceText.split(separator);

        for(String src: sources)
        {
            if (src != null && !src.equals(" ") && !src.isEmpty())
            {
                outIntegerArray[returnCount++] = Integer.parseInt(src);
            }
        }

        return returnCount;
    }

    public int bmpEncode(String de, byte[] outBmp)
    {
        byte[] bBitmaps = new byte[8];

        int[] iDEs = new int[30];
        int iDEsCount = splitIntegerText(de, " ", iDEs);
        int i;

        for (i=0; i < iDEsCount; i++)
        {
            int iFieldNumber = iDEs[i] - 1;
            if (iFieldNumber < 0)
                continue;

            byte iBit = (byte)(0x80 >> (iFieldNumber % 8));
            int iByteNumber = iFieldNumber / 8;
            if (iByteNumber >= bBitmaps.length)
                continue;

            bBitmaps[iByteNumber] |= iBit;
        }

        //strDataOut = CommonFunctions.ByteArrayToHexString(bBitmaps, " ");
        ByteOps.memcpy(outBmp, 0, bBitmaps, 0, bBitmaps.length);

        return bBitmaps.length;
    }

    public int bmpAlterBit(int deNumber, byte newBit, byte[] bmp, int bmpOffset)
    {
        if(true)
        {
            int iFieldNumber = deNumber-1;//iDEs[i] - 1;
            if (iFieldNumber < 0)
                return 0;

            byte iBit = (byte)(0x80 >> (iFieldNumber % 8));
            int iByteNumber = iFieldNumber / 8;
            //if (iByteNumber >= sizeof(bBitmaps))
            //    continue;

            if(newBit == 0)//clear
            {
                iBit = (byte) ~iBit;
                bmp[iByteNumber+bmpOffset] &= iBit;
            }
            else
            {
                bmp[iByteNumber+bmpOffset] |= iBit;
            }
        }

        return 1;
    }

    //return 1 indicate exist, 0 indicate absent
    public int bmp_checkbit(byte[] bmp8, int fieldNumber)
    {
        int i=fieldNumber;

        byte bytePos, bitmap;
        bytePos = (byte)((i-1)/8);
        bitmap = (byte)(0x80 >> ((i-1)%8));

        if((bmp8[bytePos] & bitmap) > 0)
        {
            return 1;
        }
        else
        {
            return 0;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int msgDecode(byte[] isoMsg, int isoMsgOffset, int isoMsgLen, byte[] destDbBuf)
    {
        int iThisLen = 0;
        byte[] ptrBmp = new byte[64];
        int i, iFieldCount=0;

        if (isoMsgLen < 10)
        {
            this.sysPrint("ISOU:ERR: ISO Message must be at least 10 bytes: MsgType[2] + Bitmap[8] + ...[n]");
            return TerminalConstants.iso.err.invalidInputLen;
        }

        //
        // TPDU HEADER
        //
        iThisLen = 5;
        etag.addTlvByTv(TerminalConstants.iso.tag.TPDU_RESP, isoMsg, isoMsgOffset, iThisLen, destDbBuf);
        isoMsgOffset += iThisLen;
        isoMsgLen -=iThisLen;

        //
        //MTI
        //
        iThisLen = 2;
        etag.addTlvByTv(TerminalConstants.iso.tag.MTI_RESP, isoMsg, isoMsgOffset, iThisLen, destDbBuf);
        isoMsgOffset += iThisLen;
        isoMsgLen -=iThisLen;

        //
        // BMP
        //
        ByteOps.memcpy(ptrBmp, 0, isoMsg, isoMsgOffset, 8);
        //ptrBmp = isoMsg;
        iThisLen = 8;
        etag.addTlvByTv(TerminalConstants.iso.tag.BMP_RESP, isoMsg, isoMsgOffset, iThisLen, destDbBuf);
        isoMsgOffset += iThisLen;
        isoMsgLen -=iThisLen;

        for(i=1; i<=64; i++)
        {
            byte bytePos, bitmap;
            bytePos = (byte)((i-1)/8);
            bitmap = (byte)(0x80 >> ((i-1)%8));

            //check printf("b[[%d]:%02X&%02X  \n", bytePos, ptrBmp[bytePos], bitmap);
            if(ByteOps.Byte2Int((byte)(ptrBmp[bytePos] & bitmap)) > 0)
            {

                iFieldCount++;

                int iFieldVarLen = ISO_MSG_DEVAR_LEN[i];
                int iFieldFixLen = ISO_MSG_DEFIX_LEN[i];
                char cFieldType = ISO_MSG_DETYPE[i];

                int iActualLen = 0;
                if(iFieldFixLen>0)
                {
                    iActualLen = iFieldFixLen;
                }
                else if(iFieldVarLen > 0)
                {
                    iActualLen = ByteOps.bcd2bin(isoMsg, isoMsgOffset, iFieldVarLen);
                    isoMsgOffset += iFieldVarLen;
                    isoMsgLen-=iFieldVarLen;

                    if(cFieldType =='n')
                    {
                        if(iActualLen % 2 != 0)
                            iActualLen += 1;

                        iActualLen /= 2;
                    }
                }
                else
                {
                    this.sysPrint("ISOU: Unable to decode ISO DE" + String.valueOf(i) + ". Unknown len type");
                    return TerminalConstants.iso.err.fileOutOfRange;
                }

                if(isoMsgLen<0)
                {
                    this.sysPrint("ISOU:ERR: Unable to decode ISO MSG at DE" + String.valueOf(i) + ". Insufficient Len");
                    return TerminalConstants.iso.err.fileOutOfRange;
                }

                this.sysPrint("\tDE" + String.valueOf(i) + ": " + HexUtil.bytesToHexString(isoMsg, isoMsgOffset, iActualLen));
                //this.sysPrint("", isoMsg, 0, iActualLen);
                //this.sysPrint("\tDE" + String.valueOf(i) + "(x2): " + HexUtil.bytesToHexString(isoMsg, isoMsgOffset, iActualLen));
                //this.sysPrint("", isoMsg, 0, iActualLen);
                etag.addTlvByTv("BF" + String.format("%02d", i), isoMsg, isoMsgOffset, iActualLen, destDbBuf);
                isoMsgOffset += iActualLen;
                isoMsgLen -=iActualLen;
            }
        }
        this.sysPrint("\tDE TOTAL:" + String.valueOf(iFieldCount));

        //
        // Write to file
        //

        return iFieldCount;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int msgDecode(byte[] isoMsg, int isoMsgOffset, int isoMsgLen, byte[] destDbBuf, int[]destDbBufLen)
    {
        int destLen = destDbBufLen[0];
        int iThisLen = 0;
        byte[] ptrBmp = new byte[64];
        int i, iFieldCount=0;

        if (isoMsgLen < 10)
        {
            sysPrint("ISOU:ERR: ISO Message must be at least 10 bytes: MsgType[2] + Bitmap[8] + ...[n]");
            return TerminalConstants.iso.err.invalidInputLen;
        }

        //
        // TPDU HEADER
        //
        iThisLen = 5;
        destLen = etag.addTlvByTv(TerminalConstants.iso.tag.TPDU_RESP, isoMsg, isoMsgOffset, iThisLen, destDbBuf);
        isoMsgOffset += iThisLen;
        isoMsgLen -= iThisLen;

        //
        //MTI
        //
        iThisLen = 2;
        destLen = etag.addTlvByTv(TerminalConstants.iso.tag.MTI_RESP, isoMsg, isoMsgOffset, iThisLen, destDbBuf);
        isoMsgOffset += iThisLen;
        isoMsgLen -= iThisLen;

        //
        // BMP
        //
        ByteOps.memcpy(ptrBmp, 0, isoMsg, isoMsgOffset, 8);
        //ptrBmp = isoMsg;
        iThisLen = 8;
        destLen = etag.addTlvByTv(TerminalConstants.iso.tag.BMP_RESP, isoMsg, isoMsgOffset, iThisLen, destDbBuf);
        isoMsgOffset += iThisLen;
        isoMsgLen -= iThisLen;

        for(i=1; i<=64; i++)
        {
            byte bytePos, bitmap;
            bytePos = (byte)((i-1)/8);
            bitmap = (byte)(0x80 >> ((i-1)%8));

            //check printf("b[[%d]:%02X&%02X  \n", bytePos, ptrBmp[bytePos], bitmap);
            if(ByteOps.Byte2Int((byte)(ptrBmp[bytePos] & bitmap)) > 0)
            {

                iFieldCount++;

                int iFieldVarLen = ISO_MSG_DEVAR_LEN[i];
                int iFieldFixLen = ISO_MSG_DEFIX_LEN[i];
                char cFieldType = ISO_MSG_DETYPE[i];

                int iActualLen = 0;
                if(iFieldFixLen>0)
                {
                    iActualLen = iFieldFixLen;
                }
                else if(iFieldVarLen > 0)
                {
                    iActualLen = ByteOps.bcd2bin(isoMsg, isoMsgOffset, iFieldVarLen);
                    isoMsgOffset += iFieldVarLen;
                    isoMsgLen-=iFieldVarLen;

                    if(cFieldType =='n')
                    {
                        if(iActualLen % 2 != 0)
                            iActualLen += 1;

                        iActualLen /= 2;
                    }
                }
                else
                {
                    sysPrint("ISOU: Unable to decode ISO DE" + i + ". Unknown len type");
                    return TerminalConstants.iso.err.fileOutOfRange;
                }

                if(isoMsgLen<0)
                {
                    sysPrint("ISOU:ERR: Unable to decode ISO MSG at DE" + i + ". Insufficient Len");
                    return TerminalConstants.iso.err.fileOutOfRange;
                }

                sysPrint("\tDE" + i + ": " + HexUtil.bytesToHexString(isoMsg, isoMsgOffset, iActualLen));
                destLen = etag.addTlvByTv("BF" + String.format("%02d", i), isoMsg, isoMsgOffset, iActualLen, destDbBuf);
                isoMsgOffset += iActualLen;
                isoMsgLen -= iActualLen;
            }
        }
        sysPrint("\tDE TOTAL:" + iFieldCount);

        destDbBufLen[0] = destLen;
        return iFieldCount;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int msgDecodeWithoutTpdu(byte[] isoMsg, int isoMsgOffset, int isoMsgLen, byte[] destDbBuf)
    {
        int iThisLen = 0;
        byte[] ptrBmp = new byte[64];
        int i, iFieldCount=0;

        if (isoMsgLen < 10)
        {
            this.sysPrint("ERR: ISO Message must be at least 10 bytes: MsgType[2] + Bitmap[8] + ...[n]");
            return TerminalConstants.iso.err.invalidInputLen;
        }

        //
        //MTI
        //
        iThisLen = 2;
        etag.addTlvByTv(TerminalConstants.iso.tag.MTI_RESP, isoMsg, isoMsgOffset, iThisLen, destDbBuf);
        isoMsgOffset += iThisLen;
        isoMsgLen -=iThisLen;

        //
        // BMP
        //
        ByteOps.memcpy(ptrBmp, 0, isoMsg, isoMsgOffset, 8);
        //ptrBmp = isoMsg;
        iThisLen = 8;
        etag.addTlvByTv(TerminalConstants.iso.tag.BMP_RESP, isoMsg, isoMsgOffset, iThisLen, destDbBuf);
        isoMsgOffset += iThisLen;
        isoMsgLen -=iThisLen;

        for(i=1; i<=64; i++)
        {
            byte bytePos, bitmap;
            bytePos = (byte)((i-1)/8);
            bitmap = (byte)(0x80 >> ((i-1)%8));

            //check printf("b[[%d]:%02X&%02X  \n", bytePos, ptrBmp[bytePos], bitmap);
            if((ptrBmp[bytePos] & bitmap) > 0)
            {

                iFieldCount++;

                int iFieldVarLen = ISO_MSG_DEVAR_LEN[i];
                int iFieldFixLen = ISO_MSG_DEFIX_LEN[i];
                char cFieldType = ISO_MSG_DETYPE[i];

                int iActualLen = 0;
                if(iFieldFixLen>0)
                {
                    iActualLen = iFieldFixLen;
                }
                else if(iFieldVarLen > 0)
                {
                    iActualLen = ByteOps.bcd2bin(isoMsg, isoMsgOffset, iFieldVarLen);
                    isoMsgOffset += iFieldVarLen;
                    isoMsgLen-=iFieldVarLen;

                    if(cFieldType == 'n')
                    {
                        if(iActualLen % 2 != 0)
                            iActualLen += 1;

                        iActualLen /= 2;
                    }
                }
                else
                {
                    this.sysPrint("ISOU: Unable to decode ISO DE" + String.valueOf(i) + ". Unknown len type");
                    return TerminalConstants.iso.err.fileOutOfRange;
                }

                if(isoMsgLen<0)
                {
                    this.sysPrint("ISOU:ERR: Unable to decode ISO MSG at DE" + String.valueOf(i) + ". Insufficient Len");
                    return TerminalConstants.iso.err.fileOutOfRange;
                }

                this.sysPrint("\tDE" + String.valueOf(i) + ": " + ByteOps.byteArrayToHexString(isoMsg, isoMsgOffset, iActualLen, " "));
                //this.sysPrint("", isoMsg, 0, iActualLen);
                this.sysPrint("\tDE" + String.valueOf(i) + "(x2): " + ByteOps.byteArrayToHexString(isoMsg, isoMsgOffset, iActualLen, " "));
                //this.sysPrint("", isoMsg, 0, iActualLen);
                etag.addTlvByTv("BF" + String.format("%02d", i), isoMsg, isoMsgOffset, iActualLen, destDbBuf);
                isoMsgOffset += iActualLen;
                isoMsgLen -=iActualLen;
            }
        }
        this.sysPrint("\tDE TOTAL:" + String.valueOf(iFieldCount));
        return iFieldCount;
    }
}

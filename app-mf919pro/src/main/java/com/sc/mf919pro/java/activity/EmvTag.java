package com.sc.mf919pro.java.activity;

import android.os.Build;

import androidx.annotation.RequiresApi;import utils.HexUtil;

import java.io.Serializable;

public class EmvTag implements Serializable
{
    public Tlv tlv;
    //public common.clsUtils util;

    byte[] tagptr;
    byte[] tempbuf;
    byte[] tempbuf2;

    int bufSizeOff = 0;
    int bufTagOff = 2;
    int bufMax = 2500;

    //private static final String TAG = "EMVTAG";

    public EmvTag()
    {
        tlv = new Tlv();
        //util = new common.clsUtils();

        tagptr = new byte[1124];
        tempbuf = new byte[1124];
        tempbuf2 = new byte[1124];
    }

    private static void sysPrint(String message)
    {
        Utils.debugLogPrint("EMVTAG",message);
    }

    private static void sysPrint(String message, byte[] data, int dataOffset, int dataLen)
    {
        //Utils.debugLogPrint(TAG, Utils.byteArrayToHexString(data, dataOffset, dataLen));
        Utils.debugLogPrint("EMVTAG", message + HexUtil.bcd2str(data, dataOffset, dataLen));
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int addTlvsTo(byte[] tags, int tagslen, byte[] destetagbuf)
    {
        String[] strTag = new String[4];
        int iThisTlvLen = 0;
        int iTotalTlvLen = 0;
        int iFilterNullLen = 0;
        int[] iLenOffset = new int[2];
        int[] iLenSize = new int[2];
        int[] iValueOffset = new int[2];
        int[] iValueSize = new int[2];
        int iOffset  = 0;
        //#ifdef AUTO_INDEX
        //    int iValueOffset=0;
        //    int iValueSize=0;
        //#endif

        strTag[0] = null;
        iLenOffset[0] = 0;
        iLenSize[0] = 0;
        iValueOffset[0] = 0;
        iValueSize[0] = 0;

        short sTagSize = 0;
        sTagSize =  (short)Utils.get_ushort(destetagbuf, 0);
        //filter incoming tags (cut off null ending)

        //eutil_debug_printbytes("etag_add_tlvs_to(...): ", tags, tagslen);

        //this.sysPrint("\t[+]TLV: ", tags, 0, tagslen);
        // The [+]TLV echo was removed: it re-printed the ENCODED form of the same data the
        // "TLV: AddTag <tag> Data=<hex>" line in addTlvByTv already records -- ~40 lines per
        // sale for no extra information. The AddTag line is deliberately kept.

        //ek3_debug_printtagbytes( "\t[+]EK3_DB Add", tag, value, valuelen);
	        /*
	        iFilterNullLen = tlv_filter_null(tags, tagslen);
	        if(iFilterNullLen>0)
	        {
		        eutil_debug_printbytes("etag_add_tlvs_to(): Filter Null", tags, iFilterNullLen);

		        tags+=iFilterNullLen;
		        tagslen-=iFilterNullLen;
	        }
                 */
        Utils.memcpy(tagptr, 0, tags, 0, tagslen);
        //Array.Copy(tags, 0, tagptr, 0, tagslen);

        while(tagslen>0)
        {
            //#ifdef AUTO_INDEX
            //iThisTlvLen = tlv_get_next_tag(tagptr, tagslen, &ulTag, 0, 0, &iValueOffset, &iValueSize);
            //#else
            iThisTlvLen = tlv.getNextTag(tagptr, iOffset, tagslen, strTag, iLenOffset, iLenSize, iValueOffset, iValueSize);
            //#endif

            if(iThisTlvLen > 0)
            {
                //char tempdisplay[100];
                iTotalTlvLen += iThisTlvLen;

                iOffset += iThisTlvLen;
                tagslen -= iThisTlvLen;

                //sprintf(tempdisplay, "Tag found %04X, Total Length %d. Total Length now %d", ulTag, iThisTlvLen, iTotalTlvLen);
                //ek2_debug_print(tempdisplay);
            }
            else
            {
                //this.sysPrint("etag_add_tlvs_to(): No more tags found");
                Utils.printLog("etag_add_tlvs_to(): No more tags found");
                break;
            }
        }

        //if(tagslen != 0)
        //    eutil_debug_printbytes("Filtered Null", tagptr, tagslen);

        tagslen = iTotalTlvLen;//update

        //Make sure the buf size enough to store
        if((sTagSize + tagslen + 2) > bufMax)
        {
            //this.sysPrint("CAUTION: etag_add_tlvs_to(..) - Dest Buffer Full");
            Utils.printLog("CAUTION: etag_add_tlvs_to(..) - Dest Buffer Full");
            return -1;
        }

        //Utils.debugLogPrint("TAG",""+destetagbuf.length);
        //Utils.debugLogPrint("TAG",""+bufTagOff+sTagSize);
        Utils.memcpy(destetagbuf, bufTagOff+sTagSize, tags, 0, tagslen);

        //#ifdef AUTO_INDEX /*Indexing*/
        //if((DBINDEX_CURRENT_DB !=0) && (DBINDEX_CURRENT_DB == destetagbuf))
        //{
        //    DBINDEX_TAG[DBINDEX_COUNT] = ulTag;
        //    DBINDEX_DATA_PTR[DBINDEX_COUNT] = (unsigned char*)(destetagbuf+ETAG_BUF_TAG_OFF+sTagSize+iValueOffset);
        //    DBINDEX_DATA_LENGTH[DBINDEX_COUNT] = iValueSize;
        //
        //    #ifdef PRINT_INDEXING
        //    ek3_debug_printtagbytes("\t[Indexed]", DBINDEX_TAG[DBINDEX_COUNT], DBINDEX_DATA_PTR[DBINDEX_COUNT], DBINDEX_DATA_LENGTH[DBINDEX_COUNT]);
        //    #endif
        //
        //    DBINDEX_COUNT++;
        //}
        //#endif


        sTagSize += (short)tagslen;
        Utils.set_short(sTagSize, destetagbuf);

        //eutil_debug_printbytes("TLV BUF NOW", destetagbuf, sTagSize+2);

        return sTagSize;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int addTlvByTv(String tag, byte[] data, int dataOffset, int dataLen, byte[] destBuf)
    {
        int iPointer=0, iRet=0;
        Utils.memset(tempbuf2, (byte) 0, tempbuf2.length);

        //eutil_debug_printbytes("tag=", &tag, 4);
        //this.sysPrint("EMV:TLV: AddTag " + tag.ToString("X"));
        //this.sysPrint(" Data=", data, dataOffset, dataLen);
        this.sysPrint("TLV: AddTag " + tag + " Data=" + HexUtil.bytesToHexString(data, dataOffset, dataLen));
        //Utils.printLog(" Data=" + HexUtil.bytesToHexString(data, dataOffset, dataLen));

        iRet = tlv.encodeTag(tag, tempbuf2, iPointer);
        if(iRet<=0)
            return -1;
        iPointer+=iRet;

        //print_buf("\t1:", etag_tempbuf, iPointer);

        //eutil_debug_printbytes("valuelen=", 10, valuelen);


        iRet = tlv.encodeLen(dataLen, tempbuf2, iPointer);
        if(iRet<=0)
            return -1;
        iPointer+=iRet;
        //print_buf("\t2:", etag_tempbuf, iPointer);

        //eutil_debug_printbytes("value=", value, valuelen);
        //print_buf("::::::value now=", value, valuelen); --> check corrupted data
        iRet = tlv.encodeValue(data, dataOffset, dataLen, tempbuf2, iPointer);
        if(iRet<=0)
            return -1;
        iPointer+=iRet;
        //print_buf("\t3:", etag_tempbuf, iPointer);

        iRet = addTlvsTo(tempbuf2, iPointer, destBuf);
        return iRet;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int addTlvByTvHexString(String tag, String hexString, int offset, int hexStringLen, byte[] destBuf)
    {
        Utils.memset(tempbuf, (byte)0, tempbuf.length);
        String strHexString = "";//must initilize
        //unsigned char bHexData[512]={0};//must initializetempbuf

        if (hexStringLen == 0)
            hexStringLen = Utils.strlen(hexString);

        //memcpy(strHexString, hexstring, hexstringlen);
        strHexString = hexString.substring(offset, offset+hexStringLen);

        if (Utils.strlen(strHexString) % 2 != 0)
            strHexString = strHexString + "0";//padding with zero

        //printf("addTv HexString[%d]= %s\n", strlen(strHexString), strHexString);
        //int iHexDataLen = hexstring_to_hexarray(strHexString, strlen(strHexString), bHexData);
        //int iHexDataLen = util.hexStringToByteArray(strHexString, tempbuf, 0);
        tempbuf = HexUtil.hexStringToByte(strHexString);
        int iHexDataLen = tempbuf.length;
        //printf("addTvHex %X, Len=%d\n", tag, iHexDataLen);

        return addTlvByTv(tag, tempbuf, 0, iHexDataLen, destBuf);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int addTlvByTvHexString_withPadding(String tag, String hexString, int offset, int hexStringLen, String padChar, byte[] destBuf)
    {
        Utils.memset(tempbuf, (byte)0, tempbuf.length);
        String strHexString = "";//must initilize
        //unsigned char bHexData[512]={0};//must initializetempbuf

        if (hexStringLen == 0)
            hexStringLen = Utils.strlen(hexString);

        //memcpy(strHexString, hexstring, hexstringlen);
        strHexString = hexString.substring(offset, offset+hexStringLen);

        if (Utils.strlen(strHexString) % 2 != 0)
            strHexString = strHexString + padChar;//padding with zero

        //printf("addTv HexString[%d]= %s\n", strlen(strHexString), strHexString);
        //int iHexDataLen = hexstring_to_hexarray(strHexString, strlen(strHexString), bHexData);
        //int iHexDataLen = Utils.hexStringToByteArray(strHexString, tempbuf, 0);
        tempbuf = HexUtil.hexStringToByte(strHexString);
        int iHexDataLen = tempbuf.length;
        //printf("addTvHex %X, Len=%d\n", tag, iHexDataLen);

        return addTlvByTv(tag, tempbuf, 0, iHexDataLen, destBuf);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int getValueFrom(byte[] dataBuf, String tag, byte[] outValue, int outValueOffset)
    {
        short sTagSize;
        int iRet;
        int[] endoffset = new int[2];

        endoffset[0] = 0;

        //if (DBINDEX_CURRENT_DB == frombuf)
        //{
        //    return etagindex_get_tlv(tag, value);
        //}

        sTagSize = (short)Utils.get_ushort(dataBuf, 0);
        //frombuf += 2;

        iRet = tlv.getValue(dataBuf, 2, (int)sTagSize+2, tag, outValue, endoffset);
        //this.sysPrint("GetValue:Tag=" + tag + " iRet=" + String.valueOf(iRet));

        return iRet;
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public int getValueFrom(byte[] srcBuf, String tag, byte[] outValue)
    {
        return getValueFrom(srcBuf, tag, outValue, 0);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int removeTlvByT(byte[] dataBuf, String tag)
    {
        int iTagSize;

        //if (DBINDEX_CURRENT_DB == frombuf)
        //{
        //    indexRemoveTlv(tag);
        //}

        //sTagSize = (short)Utils.get_ushort(dataBuf, 0);
        //frombuf += 2;
        iTagSize = dataBuf.length;

        //print_buf("[T]BEFORE", frombuf, sTagSize);
        //Utils.debugLogPrint("TAG", "markoffTag: " + HexUtil.bytesToHexString(dataBuf));
        tlv.markoffTag(dataBuf, 2, iTagSize, tag);
        //print_buf("[T]AFTER ", frombuf, sTagSize);
        //Utils.debugLogPrint("TAG", "markoffTag: " + HexUtil.bytesToHexString(dataBuf));

        //this.sysPrint("\t[-]TLV: " + tag.ToString("X"));
        Utils.printLog("\t[-]TLV: " + String.valueOf(tag));

        return 0;
    }
}

package emv;

import constants.TerminalConstants;

import android.os.Build;

import androidx.annotation.RequiresApi;import utils.HexUtil;

import utils.ByteOps;
import utils.AmountFormat;
import java.io.Serializable;

public class Tlv implements Serializable
{
    //public common.clsUtils util;

    String[] tagFindTag;
    String[] tagFindCount;
    int tagFindIndex = 0;

    static final int MAX_TAG_SIZE = 4;
    static final int MAX_NULL_LEN = 512;
    int maxTagSize = 4;
    int maxNullLen = 512;

    //private static final String TAG = "TLV";

    public Tlv()
    {
        //util = new common.clsUtils();

        tagFindTag = new String[5000];
        tagFindCount = new String[5000];
    }

    private static void sysPrint(String message)
    {
        timber.log.Timber.tag("TLV").d(message);
    }

    private static void sysPrint(String message, byte[] data, int dataOffset, int dataLen)
    {
        //timber.log.Timber.tag(TAG).d(ByteOps.byteArrayToHexString(data, dataOffset, dataLen));
        timber.log.Timber.tag("TLV").d(HexUtil.bcd2str(data, dataOffset, dataLen));
    }

    public int encodeTag(String tag, byte[] dest, int destOffset)
    {
        boolean doTheRest = false;
        byte ucTag = 0;
        int iPointer = destOffset;

        byte[] bTag = new byte[4];
        bTag = HexUtil.hexStringToByte(tag);
        //timber.log.Timber.tag(TAG).d("encodeTag: " + tag + "-->" + bTag.length);

        //Max up to 4 bytes tag
        //ucTag = (byte)((tag&0xFF000000) >> 24);
        if(bTag.length>0)
        {
            ucTag = bTag[0];
            if(ucTag!=0)
            {
                dest[iPointer++] = ucTag;
                doTheRest = true;
            }
        }

        if(bTag.length>1)
        {
            //ucTag = (byte)((tag&0x00FF0000) >> 16);
            ucTag = bTag[1];
            if(doTheRest || (ucTag!=0))
            {
                dest[iPointer++] = ucTag;
                doTheRest = true;
            }
        }

        if(bTag.length>2)
        {
            //ucTag = (byte)((tag&0x0000FF00) >> 8);
            ucTag = bTag[2];
            if(doTheRest || (ucTag!=0))
            {
                dest[iPointer++] = ucTag;
                doTheRest = true;
            }
        }

       if(bTag.length>3)
       {
           //ucTag = (byte)(tag&0x000000FF);
           ucTag = bTag[3];
           if(doTheRest || (ucTag!=0))
           {
               dest[iPointer++] = ucTag;
               doTheRest = true;
           }
       }

        if(iPointer == 0)
            return -1;

        return iPointer;
    }

    public int encodeLen(int len, byte[] dest, int destOffset)
    {
        if(len<128)
        {
            dest[destOffset] = (byte)len;
            return 1;
        }
        else if(len<256)
        {
            dest[destOffset] = (byte) 0x81;
            destOffset++;
            dest[destOffset] = (byte)len;
            return 2;
        }
        else if(len<0x010000)
        {
            dest[destOffset] = (byte) 0x82;
            destOffset++;
            ByteOps.set_ushort(len, dest, destOffset);
            return 3;
        }
        else
            return -1;
    }

    public int encodeValue(byte[] value, int valueoffset, int valuelen, byte[] dest, int destOffset)
    {
        //this.sysPrint("valueoffset=" + String.valueOf(valueoffset));
        //this.sysPrint("valuelen=" + String.valueOf(valuelen));
        //this.sysPrint("valuelen=" + value.length);
        //timber.log.Timber.tag("Utils").d("value=" + HexUtil.bytesToHexString(value, valueoffset, valuelen));
        if(valuelen>0)
        {
            ByteOps.memcpy(dest, destOffset, value, valueoffset, valuelen);
        }

        return valuelen;
    }

    public int encodeTagLenValue(String tag, byte[] value, int valueoffset, int valuelen, byte[] dest, int destOffset)
    {
        int iAffectedlen = 0;
        int iPointer = 0;
        //unsigned char* initialDest = dest;

        //
        // Tag
        //
        iAffectedlen = encodeTag(tag, dest, iPointer+destOffset);
        if(iAffectedlen<0)
            return iAffectedlen;
        iPointer += iAffectedlen;

        //
        // Len
        //
        iAffectedlen = encodeLen(valuelen, dest, iPointer+destOffset);
        if(iAffectedlen<0)
            return iAffectedlen;
        iPointer += iAffectedlen;

        //
        // Value
        //
        iAffectedlen = encodeValue(value, valueoffset, valuelen, dest, iPointer+destOffset);
        if(iAffectedlen<0)
            return iAffectedlen;
        iPointer += iAffectedlen;

        return iPointer;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int decodeLen(byte[] lenptr, int lenptrOffset, int[] affectedlen)
    {
        int iLen = Byte.toUnsignedInt(lenptr[lenptrOffset]);
        affectedlen[0] = 0;

        if(iLen < 0x80)
        {
            affectedlen[0] = 1;
            return iLen;
        }

        if(iLen == 0x81)
        {
            affectedlen[0] = 2;

            iLen = Byte.toUnsignedInt(lenptr[lenptrOffset+1]);
            return iLen;
        }

        if(iLen == 0x82)
        {
            affectedlen[0] = 3;

            /*
            //lenptr++;
            iLen = Byte.toUnsignedInt(lenptr[lenptrOffset + 1]);//bug fixed
            iLen = iLen << 8;//bug fixed

            //lenptr++;
            iLen += (int)lenptr[lenptrOffset + 2];
            * */

            //Bug Fix for taking wrong length
            int len1 = Byte.toUnsignedInt(lenptr[lenptrOffset + 1]);
            len1  = len1 << 8;

            int len2 = Byte.toUnsignedInt(lenptr[lenptrOffset + 2]);

            iLen = len1 + len2;

            return iLen;
        }

        return TerminalConstants.iso.err.invalidInputLen;//invalid len coding
    }

    public String decodeTag(byte[] tagptr, int tagptrOffset, int[] affectedlen)
    {
        byte[] thisTag = new byte[2];
        int iPointer = tagptrOffset;
        int iTagSize = 0;
        byte bTag=0;

        affectedlen[0] = 0;

        //Skip null data, 0x00 and 0xFF
        //while(*(tagptr+iPointer) == 0x00 || *(tagptr+iPointer) == 0xFF)
        while(tagptr[iPointer] == 0x00)//Paypass3.0 Test Case 3M50-0005(send_old_tornTrx_inDiscData) - 0xFF is not NULL
        {
            iPointer++;
            if(iPointer > MAX_NULL_LEN)//max check len = 32
            {
                timber.log.Timber.tag("Utils").d("TLV:TagNotFound: Exceeded MAX NULL(2)");
                return null/*global.tlv.err.tagNotFound*/;//no tag found
            }
        }

        bTag = tagptr[iPointer];
        iPointer++;
        thisTag[0] = bTag;
        iTagSize++;

        //
        // One Byte Tag
        //
        if((bTag & 0x1F) != 0x1F)//if first byte is not AND 0x1F, then the tag is only one byte
        {
            affectedlen[0] = iPointer - tagptrOffset;

            return HexUtil.byteToHex(thisTag[0]);
        }

        //
        // Two or more byte tag
        //
        //GET_MORE_TAG:
        while(true)
        {
            bTag = tagptr[iPointer];
            iPointer++;

            //ulTag = ulTag << 8;
            //ulTag += bTag;
            thisTag[1] = bTag;
            iTagSize++;
            if (iTagSize > MAX_TAG_SIZE)
                return null;/*global.tlv.err.tagNotFound*/;

            if ((bTag & 0x80) == 0x80)//if subsequent tag AND 0x80, then there is another byte next
		        continue;//goto GET_MORE_TAG;

            affectedlen[0] = iPointer - tagptrOffset;
            break;
        }

        return HexUtil.bytesToHexString(thisTag, 0, 2);
    }

    /*
     * get the next(first) TAG's data, it TAG, LEN ptr, and VALUE ptr
     * Input:
     * datain - ptr to data in
     * datainlen - length of data in
     * Output:
     * tag - TAG ptr
     * len_offset - offset of LEN from the TAG position
     * len_size - size of LEN
     * value_offset - offset of VALUE from the TAG position
     * value_size = size of the VALUE
     * Return:
     * Total affected length of TLV that extracted.
     */


    public int filterNull(byte[] datain, int dataOffset, int datainlen)
    {
        //Return: the new pointer
        int iPointer = dataOffset;
        //unsigned char isFF = 0;

        //
        // Filtering - to be implement - to ignore 0x00 and 0xFF
        //
        //while(*(datain+iPointer) == 0x00 || *(datain+iPointer) == 0xFF)
        while(datain[iPointer] == 0x00)//Paypass3.0 Test Case 3M50-0005(send_old_tornTrx_inDiscData) - 0xFF is not NULL
        {

            iPointer++;//filter

            if(iPointer > datainlen)
            {
                //hh_debug_printdec("tag null ended", iPointer);
                //return TLV_ER_TAG_NOT_FOUND;
                break;
            }

            if(iPointer > maxNullLen)//max check len = 32
                break;
            //return TLV_ER_TAG_NOT_FOUND;//no tag found
        }

        return iPointer;
    }

    public int filterNullMc(byte[] datain, int dataOffset, int datainlen)
    {
        //Return: the new pointer
        int iPointer = dataOffset;
        //unsigned char isFF = 0;

        //
        // Filtering - to be implement - to ignore 0x00 and 0xFF
        //

        while(datain[iPointer] == 0x00 || datain[iPointer] == 0xFF)
        {
            if(datain[iPointer] == 0xFF)
            {
                if((iPointer+1)>=datainlen)//If this is the last byte, filter
                {
				    //goto FILTER;
                }
                else {
                    if (datain[iPointer + 1] == 0x00 || datain[iPointer + 1] == 0xFF)
                    {
                        //goto FILTER;
                    }
                    else
                    {
                        break;//Stop
                    }
                }
            }


            //FILTER:
            iPointer++;//filter

            if(iPointer > datainlen)
            {
                //hh_debug_printdec("tag null ended", iPointer);
                //return TLV_ER_TAG_NOT_FOUND;
                break;
            }

            if(iPointer > maxNullLen)//max check len = 32
                break;
            //return TLV_ER_TAG_NOT_FOUND;//no tag found
        }

        return iPointer;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int getNextTag(byte[] datain, int datainOffset, int datainlen, String[] tag, int[] len_offset, int[] len_size, int[] value_offset, int[] value_size)
    {
        //Return: Affected Length (Entire TLV);

        String strTag = null;
        int iLen = 0;

        int iPointer = datainOffset;
        int[] iAffectedLength = new int[2];

        iAffectedLength[0] = 0;

        //
        // Filtering - to be implement - to ignore 0x00 and 0xFF
        //
        //while(*(datain+iPointer) == 0x00 || *(datain+iPointer) == 0xFF)
        while(datain[iPointer] == 0x00)//Paypass3.0 Test Case 3M50-0005(send_old_tornTrx_inDiscData) - 0xFF is not NULL
        {
            iPointer++;
            if(iPointer > datainlen)
            {
                //hh_debug_printdec("tag null ended", iPointer);
                timber.log.Timber.tag("Utils").d("TLV:TagNotFound: Exceeded input limit");
                return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;
            }

            if(iPointer > maxNullLen)//max check len = 32 --> need more than 200 for ISO engine
            {
                timber.log.Timber.tag("Utils").d("TLV:TagNotFound: Exceeded MAX NULL");
                return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;//no tag found
            }
            if(datain.length<=iPointer)
            {
                return TerminalConstants.tlv.err.invalidLen;
            }
        }
        if(iPointer>0)
        {
            //hh_debug_printdec("tag ignore null x", iPointer);
        }

        //
        // Get Tag
        //
        //timber.log.Timber.tag("GetTag:").d(HexUtil.bytesToHexString(datain));
        //timber.log.Timber.tag("GetTag1:").d(String.valueOf(iPointer));
        strTag = decodeTag(datain, iPointer, iAffectedLength);
        //timber.log.Timber.tag("GetTag2:").d(Arrays.toString(iAffectedLength));
        if(iAffectedLength[0]>(datainlen-iPointer))
            return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;// no more tags (finish)
        if(strTag == null)
            return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;//no tag found

        iPointer += iAffectedLength[0];

        //hh_debug_print2dec("TAG", ulTag, iAffectedLength);

        //
        // Get Len
        //
        iLen = decodeLen(datain, iPointer, iAffectedLength);
        if(iLen < 0)
            return TerminalConstants.tlv.err.invalidLen;//global.tlv.err.invalidLen;//Invalid Length
        if(iAffectedLength[0]>(datainlen-iPointer))
            return TerminalConstants.tlv.err.invalidLen;//global.tlv.err.invalidLen;// no more tags

        if(len_offset[0] != 0)
        {
            len_offset[0] = iPointer;
            len_size[0] = iAffectedLength[0];
        }

        iPointer += iAffectedLength[0];
        //hh_debug_print2dec("LEN", iLen, iAffectedLength);

        //
        // Get Value
        //
        if(iLen>(datainlen-iPointer))
            return TerminalConstants.tlv.err.invalidValue;//global.tlv.err.invalidValue;// Invalid Data

        if(value_offset[0] != 0)
        {
            value_offset[0] = iPointer;
            value_size[0] = iLen;
        }
        //hh_debug_print2dec("VALUE Offset & size", iPointer, iLen);

        //
        // Point to end of VALUE
        //
        iPointer += iLen;

        //
        // Output Tag
        //
        tag[0] = strTag;

        //
        // Return total affected length
        //
        return iPointer;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int getTlv(byte[] datain, int datainlen, String tag, byte[] value, int[] endedoffset)
    {
        String[] strTag = new String[4];
        int[] iValueOffset = new int[2];
        int[] iValueSize = new int[4];
        int iRet;
        int iEndedOffset = 0;
        int[] iLenOffset = new int[2];
        int[] iLenSize = new int[2];
        int iPosition = 0;

        strTag[0] = null;
        iValueOffset[0] = 0;
        iValueSize[0] = 0;
        iLenOffset[0] = 0;
        iLenSize[0] = 0;

        //hh_debug_printbytes("all tag bytes", datain, datainlen);

        if(tagFindIndex>=(5000-2))
            tagFindIndex = 0;

        tagFindIndex++;
        tagFindTag[tagFindIndex] = tag;


        //SEARH_NEXT:
        while(true)
        {
            tagFindCount[tagFindIndex] += 1;

            //hh_debug_printbytes("remain tag bytes", datain, datainlen);

            iRet = getNextTag(datain, iPosition, datainlen, strTag, iLenOffset, iLenSize, iValueOffset, iValueSize);

            //hh_debug_print2dec("Next Tag", ulTag, iRet);

            //
            //Is Found Tag?
            //
            if (iRet <= 0) {
                //hh_debug_print("No More Tag");
                return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;
            }

            //
            //Update Ended Offset
            //
            iEndedOffset += iRet;

            //
            //Is TAG Matched?
            //
            //hh_debug_print2dec("Tag Matched?", tag, ulTag);
            if (strTag[0] == tag) {
                if (endedoffset[0] != 0)
                    endedoffset[0] = iEndedOffset;

                ByteOps.memcpy(value, 0, datain, iPosition + iValueOffset[0], iValueOffset[0] + iValueSize[0]);
                //*value = (unsigned char*)(datain);

                //hh_debug_printbytes("Yes. Return value1", datain + iValueOffset, iValueSize);
                //hh_debug_printbytes("Yes. Return value2", *value, iValueSize);

                return iValueOffset[0] + iValueSize[0];
            }

            //
            //Point to Next
            //
            iPosition += iRet;
            datainlen -= iRet;


            //hh_debug_print("No. Go NEXT");

            //
            //Is more remaining data to check?
            //
            if (datainlen > 0)
            {
		        //goto SEARH_NEXT;
            }
            else
            {
                break;
            }
        }

        //
        // No tag found
        //
        return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int getValue(byte[] datain, int datainOffset, int datainlen, String tag, byte[] value, int[] endedOffset)
    {
        String[] strTag = new String[4];
        int[] iValueOffset = new int[2];
        int[] iValueSize = new int[2];
        int iRet;
        int iEndedOffset = 0;
        int[] iLenOffset = new int[2];
        int[] iLenSize = new int[2];
        int iPosition = datainOffset;

        strTag[0] = null;
        iValueOffset[0] = -1;
        iValueSize[0] = -1;
        iLenOffset[0] = 0;
        iLenSize[0] = 0;

        //hh_debug_printbytes("all tag bytes", datain, datainlen);

        if(tagFindIndex>=(5000-2))
            tagFindIndex = 0;

        tagFindIndex++;
        tagFindTag[tagFindIndex] = tag;
        //this.sysPrint("tagFindTag[" + String.valueOf(tagFindIndex) + "]=" + tagFindTag[tagFindIndex]);

        //SEARH_NEXT:
        while(true)
        {
            tagFindCount[tagFindIndex] += 1;

            //timber.log.Timber.tag("TAG").d("getValue:  --->" + iPosition);
            iRet = getNextTag(datain, iPosition, datainlen, strTag, iLenOffset, iLenSize, iValueOffset, iValueSize);
            //timber.log.Timber.tag("TAG").d("getValue: " + Arrays.toString(strTag));
            //timber.log.Timber.tag("TAG").d("getValue: " + Arrays.toString(iLenOffset) +"---"+ Arrays.toString(iLenSize) +"---"+ Arrays.toString(iValueOffset) +"---"+ Arrays.toString(iValueSize));

            //
            //Is Found Tag?
            //
            if (iRet <= 0)
            {
                return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;
            }

            if (iRet > datainlen)//The total affected size exceed the given source data
            {
                return TerminalConstants.tlv.err.invalidLen;//global.tlv.err.invalidLen;
            }

            //
            //Update Ended Offset
            //
            iEndedOffset += iRet;

            //
            //Is TAG Matched?
            //
            //hh_debug_print2dec("Tag Matched?", tag, ulTag);
            if (strTag[0].equals(tag))
            {
                if (endedOffset[0] != 0)
                    endedOffset[0] = iEndedOffset;
                //Log.d("TAG", HexUtil.bytesToHexString(datain));
                //Log.d("TAG", iValueOffset[0] + "");
                //Log.d("TAG", iValueSize[0] + "");
                //*value = (unsigned char*)(datain + iValueOffset);
                if(datain[iValueOffset[0]] == 0x00 && tag.equals("D3"))
                {
                    ByteOps.memcpy(value, 0, datain, /*iPosition + */iValueOffset[0]+1, /*iValueOffset + */iValueSize[0]);
                }
                else
                {
                    ByteOps.memcpy(value, 0, datain, /*iPosition + */iValueOffset[0], /*iValueOffset + */iValueSize[0]);
                }
                //hh_debug_printbytes("Yes. Return value1", datain + iValueOffset, iValueSize);
                //hh_debug_printbytes("Yes. Return value2", *value, iValueSize);
                //timber.log.Timber.tag("TLVTAG").d(HexUtil.bytesToHexString(value));

                return iValueSize[0];
            }

            //
            //Point to Next
            //
            iPosition =/*+=*/ iRet;
            //datainlen -= iRet;

            //hh_debug_print("No. Go NEXT");

            //
            //Is more remaining data to check?
            //
            //if (datainlen > 0)
            if (datainlen > iPosition)
            {
                //goto SEARH_NEXT;
            }
            else
            {
                break;
            }
        }

        //
        // No tag found
        //
        return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;
    }

    //To mark of the TLV with specify tag to all ZERO
    @RequiresApi(api = Build.VERSION_CODES.O)
    public int markoffTag(byte[] datain, int datainOffset, int datainlen, String tag)
    {
        String[] strTag = new String[4];
        int[] iValueOffset = new int[2];
        int[] iValueSize = new int[2];
        int iRet;
        int iEndedOffset = 0;
        int[] iLenOffset = new int[2];
        int[] iLenSize = new int[2];
        int iPosition = datainOffset;

        strTag[0] = null;
        iValueOffset[0] = -1;
        iValueSize[0] = -1;
        iLenOffset[0] = 0;
        iLenSize[0] = 0;

        if(tagFindIndex>=(5000-2))
            tagFindIndex = 0;

        tagFindIndex++;
        tagFindTag[tagFindIndex] = tag;

        //hh_debug_printbytes("all tag bytes", datain, datainlen);
        //this.sysPrint("all tag bytes", datain, 0, datainlen);

        //SEARH_NEXT:
        while(true)
        {
            tagFindCount[tagFindIndex] += 1;

            //hh_debug_printbytes("remain tag bytes", datain, datainlen);

            iRet = getNextTag(datain, iPosition, datainlen, strTag, iLenOffset, iLenSize, iValueOffset, iValueSize);

            //hh_debug_print2dec("Next Tag", ulTag, iRet);

            //
            //Is Found Tag?
            //
            if (iRet <= 0) {
                //hh_debug_print("No More Tag");
                return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;
            }

            if (iRet > datainlen)//The total affected size exceed the given source data
            {
                return TerminalConstants.tlv.err.invalidLen;//global.tlv.err.invalidLen;
            }

            //
            //Is TAG Matched?
            //
            //hh_debug_print2dec("Tag Matched?", tag, ulTag);
            if (strTag[0].equals(tag))
            {
                //timber.log.Timber.tag("TAG").d("markoffTag: " + HexUtil.bytesToHexString(datain));
                byte[] tmp=new byte[(datainlen-(iRet - iPosition))];
                //byte[] tmp=new byte[(datainlen- iRet)];
                ByteOps.memset(datain, iPosition/*iValueOffset*/, (byte) 0, iRet - iPosition);
                System.arraycopy(datain,0,tmp,0,iPosition);
                System.arraycopy(datain,iRet,tmp,iPosition,datainlen-iRet);
                System.arraycopy(tmp,0,datain,0,tmp.length);
                //ByteOps.memset(datain, tmp.length-1/*iValueOffset*/, (byte) 0, iRet - iPosition);
                ByteOps.memset(datain, tmp.length/*iValueOffset*/, (byte) 0, iRet - iPosition);
                int count = Integer.parseInt(HexUtil.bytesToHexString(new byte[]{datain[0],datain[1]}),16);
                //timber.log.Timber.tag("TAG").d("markoffTag: " + count + "--" + iRet + "--" + iPosition);
                byte[] hexCount=HexUtil.hexStringToByte(AmountFormat.zeroPadding(Integer.toString((count-(iRet-iPosition)),16),4));
                datain[0]=hexCount[0];
                datain[1]=hexCount[1];
                //timber.log.Timber.tag("TAG").d("markoffTag: " + HexUtil.bytesToHexString(datain));

                return iRet - iPosition;
            }

            //
            //Point to Next
            //
            iPosition =/*+=*/ iRet;
            //datainlen -= iRet;


            //hh_debug_print("No. Go NEXT");

            //
            //Is more remaining data to check?
            //
            //if(datainlen > 0)
            if (datainlen > iPosition)
            {
		        //goto SEARH_NEXT;
            }
            else
            {
                break;
            }
        }

        //
        // No tag found
        //
        return TerminalConstants.tlv.err.tagNotFound;//global.tlv.err.tagNotFound;
    }
}

package com.sc.mf919.kotlin.activity

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.utils.EmvUtil
import com.sc.mf919.kotlin.database.model.DbModelDenominationList
import com.sc.mf919.kotlin.database.model.ModelMaintenanceSchedule
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import enums.EnumDateFormat
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TokenPagerAdapter(
    private val context: Context,
    private val pages: List<List<DbModelDenominationList>>,
    private val maintenanceModel: ModelMaintenanceSchedule?,
    private val displayType: Int,
) : RecyclerView.Adapter<TokenPagerAdapter.PageViewHolder>() {
    var isMaintenance = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.page_token_list, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val txnDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            val  currDate = Utils.DateTimeFormat(txnDt)
            val formatter = DateTimeFormatter.ofPattern(EnumDateFormat.yyyyMMddHHmmss.dateFormat)

            maintenanceModel?.let {
                try{
                    val currentDate = LocalDateTime.parse(currDate, formatter)
                    val startDate = LocalDateTime.parse(it.START_TIME, formatter)
                    val endDate = LocalDateTime.parse(it.END_TIME, formatter)
                    if (currentDate.isAfter(startDate) && currentDate.isBefore(endDate)) {
                        isMaintenance = true
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }

        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(tokenList: List<DbModelDenominationList>) {
            if(displayType == 0) {
                renderOneColumn(itemView, tokenList)
            } else if (displayType == 1){
                renderTwoColumn(itemView, tokenList)
            }
        }
    }

    fun renderOneColumn(itemView: View, tokenList: List<DbModelDenominationList>) {
        val container = itemView.findViewById<LinearLayout>(R.id.tokenListContainer)
        container.removeAllViews()

        tokenList.forEachIndexed {index, tokenValue ->
            val tokenCard = LayoutInflater.from(context).inflate(R.layout.item_token, container, false)
            var amountDisplay = tokenValue.Amount
            if(amountDisplay.contains(".")) {
                val tempList = amountDisplay.split(".")
                amountDisplay = if (tempList[1] == "00") {
                    // remove decimals
                    tempList[0]
                } else {
                    // keep original
                    amountDisplay
                }
            }

            tokenCard.findViewById<TextView>(R.id.tokenPrice).text = "RM$amountDisplay"
            if(tokenValue.Desc.isNotEmpty()) {
                tokenCard.findViewById<TextView>(R.id.tokenLabel).text = "(${tokenValue.Desc})"
            }
            tokenCard.setDebouncedOnClickListener{
                if (ServiceHolder.autoSettlementIsRunning) {
                    Toast.makeText(context, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
                } else if(isMaintenance) {
                    showUnderMaintenanceDialog(context)
                    //Toast.makeText(context, "Printing Receipt Please Hold On...", Toast.LENGTH_SHORT).show()
                } else {
                    val newIntent = Intent(context, DenominationPaymentOptionActivity::class.java)
                    newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    newIntent.putExtra("denomination_product", Gson().toJson(tokenValue))
                    context.startActivity(newIntent)
                    if (context is Activity) {
                        context.finish()
                    }
                }
            }
            container.addView(tokenCard)
        }
    }

    fun renderTwoColumn(itemView: View, tokenList: List<DbModelDenominationList>) {
        val container = itemView.findViewById<LinearLayout>(R.id.tokenListContainer)
        container.removeAllViews()
        var linearListItem: LinearLayout? = null

        tokenList.forEachIndexed { index, tokenValue ->
            // Create new row for every 2 items
            if (index % 2 == 0) {
                linearListItem = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        weightSum = 2.0f
                        setMargins(0, 0, 0, Helper.getInstance().getDpValue(15))
                    }
                }
                container.addView(linearListItem) // add row only once
            }

            val tokenCard = LayoutInflater.from(context).inflate(R.layout.item_token, linearListItem, false).apply {
                //TODO Dynamic Layout For Small Terminal
                val displayMetrics = resources.displayMetrics
                val screenHeightPx = displayMetrics.heightPixels
                val screenHeightDp = screenHeightPx / displayMetrics.density

                if (screenHeightDp < 500) {
                    setPadding(10, 30, 10, 30)
                } else {
                    setPadding(10, 40, 10, 40)
                }
            }
            // Set weight so 2 items share row equally
            tokenCard.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                setMargins(5, 0, 5, 0)
            }

            var amountDisplay = tokenValue.Amount
            if (amountDisplay.contains(".")) {
                val tempList = amountDisplay.split(".")
                amountDisplay = tempList.first()
            }

            tokenCard.findViewById<TextView>(R.id.tokenPrice).text = "RM$amountDisplay"
            tokenCard.findViewById<TextView>(R.id.tokenLabel).text = "(${tokenValue.Desc})"
            tokenCard.setDebouncedOnClickListener {
                if (ServiceHolder.autoSettlementIsRunning) {
                    Toast.makeText(context, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
                } else if (isMaintenance) {
                    showUnderMaintenanceDialog(context)
                    //Toast.makeText(context, "Printing Receipt Please Hold On...", Toast.LENGTH_SHORT).show()
                } else {
                    val newIntent = Intent(context, DenominationPaymentOptionActivity::class.java)
                    newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    newIntent.putExtra("denomination_product", Gson().toJson(tokenValue))
                    context.startActivity(newIntent)
                    // finish so onDestroy cancels vendingSessionRunnable - otherwise the 10s MDB
                    // deny timer fires while the customer is paying (same as one-column flow)
                    if (context is Activity) {
                        context.finish()
                    }
                }
            }

            linearListItem?.addView(tokenCard)
        }
        /*linearListItem?.let { row ->
            if (row.childCount == 1) {
                val spacer = Space(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                row.addView(spacer)
            }
        }*/
    }

    fun showUnderMaintenanceDialog(context: Context) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.activity_dialog_under_maintain)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        CoroutineScope(Dispatchers.Main).launch {
            dialog.show()
            delay(5000)
            dialog.hide()
        }
    }
}
package com.sc.mf919pro.kotlin.fragment

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.viewpager.widget.ViewPager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.R
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import enums.EnumDateFormat
import me.relex.circleindicator.CircleIndicator
import tms.models.AdvertisementObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class FragmentImageSlider : Fragment() {
    val handler = Handler()
    private var listener: OnFragmentInteractionListener? = null

    private lateinit var viewpager: ViewPager
    private var currentPage = 0
    private val autoSlideDelay = 10000L
    val imageUrlList: MutableList<String> = mutableListOf()


    interface OnFragmentInteractionListener {
        fun fragmentImageSlideAction()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val parent = parentFragment
        if (parent is OnFragmentInteractionListener) {
            listener = parent
        } else {
            throw RuntimeException("$parent must implement OnFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_image_slider, container, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startAutoSlide() {
        val runnable = object : Runnable {
            override fun run() {
                if (currentPage == imageUrlList.size) {
                    currentPage = 0
                }
                viewpager.setCurrentItem(currentPage++, true)
                handler.postDelayed(this, autoSlideDelay)
            }
        }
        handler.postDelayed(runnable, autoSlideDelay)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val merchantConfig = ServiceHolder.getMerchantInfo()
        val jsonString = merchantConfig?.Advertisement
        var advertisementList: List<AdvertisementObject> = listOf()
        if (jsonString != null) {
            try {
                advertisementList = Gson().fromJson(jsonString, object : TypeToken<ArrayList<AdvertisementObject>>() {}.type)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        viewpager = requireView().findViewById(R.id.viewpager) as ViewPager
        val indicator = requireView().findViewById(R.id.indicator) as CircleIndicator

        if (advertisementList.isNotEmpty()) {
            for (i in advertisementList.indices) {
                try {
                    val formatter = DateTimeFormatter.ofPattern(EnumDateFormat.yyyyMMddHHmmss.dateFormat)
                    val parsedDateFrom = LocalDateTime.parse(advertisementList[i].DateFrom?.replace("T", " "), formatter)
                    val parsedDateTo = LocalDateTime.parse(advertisementList[i].DateTo?.replace("T", " "), formatter)
                    val currentDate = LocalDateTime.now()

                    val isWithinDateRange = currentDate.isEqual(parsedDateFrom) || (currentDate.isAfter(parsedDateFrom) && currentDate.isBefore(parsedDateTo))
                    if (isWithinDateRange) {
                        imageUrlList.add(advertisementList[i].ImgUrl.toString())
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }

            if (imageUrlList.isNotEmpty()) {
                val viewPagerAdapter = context?.let { ImageSlideAdapter(it, imageUrlList) }
                viewpager.adapter = viewPagerAdapter
                indicator.setViewPager(viewpager)

                if (imageUrlList.size > 1) {
                    startAutoSlide()
                }
            } else {
                val localImages = listOf("defaultBanner")
                val viewPagerAdapter = context?.let { ImageSlideAdapter(it, localImages) }
                viewpager.adapter = viewPagerAdapter
                indicator.setViewPager(viewpager)
            }
        } else {
            val localImages = listOf("defaultBanner")
            val viewPagerAdapter = context?.let { ImageSlideAdapter(it, localImages) }
            viewpager.adapter = viewPagerAdapter
            indicator.setViewPager(viewpager)
        }
    }
}
package com.swyp.test.controller

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("")
class testContrller {

    @RequestMapping("/test")
    fun test(){


        println("test 입니다.")
    }
}
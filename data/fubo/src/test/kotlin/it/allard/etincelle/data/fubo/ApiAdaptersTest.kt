// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.coroutines.Continuation
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Body

class ApiAdaptersTest {

    // What each call of the service sends and reads, as Retrofit hands it to Moshi.
    private fun apiTypes(): List<Type> = FuboApi::class.java.declaredMethods.flatMap { method ->
        val params = method.genericParameterTypes
        val bodies = method.parameterAnnotations.withIndex()
            .filter { (_, annotations) -> annotations.any { it is Body } }
            .map { params[it.index] }
        // A suspend function answers through its last parameter, a Continuation<? super T>.
        val answer = (params.lastOrNull() as? ParameterizedType)
            ?.takeIf { it.rawType == Continuation::class.java }
            ?.actualTypeArguments?.single()
            ?.let { (it as? WildcardType)?.lowerBounds?.singleOrNull() ?: it }
        bodies + listOfNotNull(answer)
    }.filterNot { type ->
        // Read by Retrofit itself, not by Moshi.
        val raw = (type as? ParameterizedType)?.rawType ?: type
        raw == okhttp3.ResponseBody::class.java || raw == retrofit2.Response::class.java
    }

    @Test
    fun `every type of the api has an adapter generated at build time`() {
        // Without the reflection factory, a Kotlin class with no generated adapter, at any depth, throws.
        val moshi = Moshi.Builder().build()
        val types = apiTypes()
        for (type in types) moshi.adapter<Any>(type)
        assertTrue(types.size > 10)
    }
}

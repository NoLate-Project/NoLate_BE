package com.noLate.global.logging

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.stereotype.Component
import org.springframework.util.ClassUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import javax.sql.DataSource

/**
 * P6Spy가 런타임 클래스패스에 있을 때만 실제 DataSource를 감싼다.
 *
 * IDE가 Gradle 의존성을 아직 동기화하지 못한 상태에서도 기본 MySQL DataSource로
 * 애플리케이션이 기동되어야 하므로 설정 파일에서 P6SpyDriver를 직접 지정하지 않는다.
 */
@Component
class OptionalP6SpyDataSourceBeanPostProcessor : BeanPostProcessor {
    private val log = LoggerFactory.getLogger(javaClass)
    private val p6DataSourceClassName = "com.p6spy.engine.spy.P6DataSource"
    private val p6SpyAvailable = ClassUtils.isPresent(p6DataSourceClassName, javaClass.classLoader)

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean !is DataSource || !p6SpyAvailable || bean.javaClass.name == p6DataSourceClassName) {
            return bean
        }

        val p6DataSource = ClassUtils.forName(p6DataSourceClassName, javaClass.classLoader)
            .getConstructor(DataSource::class.java)
            .newInstance(bean) as DataSource

        log.info("P6Spy error-only SQL logging enabled. dataSourceBean={}", beanName)

        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DataSource::class.java, AutoCloseable::class.java),
        ) { _, method, arguments ->
            if (method.name == "close" && method.parameterCount == 0) {
                (bean as? AutoCloseable)?.close()
                return@newProxyInstance null
            }

            try {
                method.invoke(p6DataSource, *(arguments ?: emptyArray()))
            } catch (exception: InvocationTargetException) {
                throw exception.targetException
            }
        }
    }
}

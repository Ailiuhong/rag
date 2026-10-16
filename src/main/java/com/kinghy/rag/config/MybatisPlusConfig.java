package com.kinghy.rag.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件。
 *
 * 没有注册这个拦截器时，MyBatis-Plus 的 page(...) 查询不会真正分页：
 * 会返回全表数据且 total 恒为 0（敏感词、日志、分类、词频、知识库文件
 * 这几个分页接口都会受影响）。项目里另一套 PageHelper 分页靠其自带的
 * 自动配置工作，两者可以共存。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}

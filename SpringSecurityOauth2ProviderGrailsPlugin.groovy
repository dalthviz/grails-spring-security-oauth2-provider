/* Copyright 2006-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.plugin.springsecurity.oauthprovider.AuthorizationRequestHolderSerializer
import grails.plugin.springsecurity.oauthprovider.GormAuthorizationCodeService
import grails.plugin.springsecurity.oauthprovider.GormClientDetailsService
import grails.plugin.springsecurity.oauthprovider.GormTokenStoreService
import grails.plugin.springsecurity.oauthprovider.OAuth2AuthenticationSerializer
import grails.plugin.springsecurity.oauthprovider.endpoint.RequiredRedirectResolver
import grails.plugin.springsecurity.oauthprovider.endpoint.WrappedAuthorizationEndpoint
import grails.plugin.springsecurity.oauthprovider.endpoint.WrappedTokenEndpoint
import grails.plugin.springsecurity.web.authentication.AjaxAwareAuthenticationEntryPoint
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.MappingJacksonHttpMessageConverter
import org.springframework.http.converter.xml.SourceHttpMessageConverter
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.oauth2.provider.CompositeTokenGranter
import org.springframework.security.oauth2.provider.DefaultAuthorizationRequestManager
import org.springframework.security.oauth2.provider.approval.TokenServicesUserApprovalHandler
import org.springframework.security.oauth2.provider.client.ClientCredentialsTokenEndpointFilter
import org.springframework.security.oauth2.provider.client.ClientCredentialsTokenGranter
import org.springframework.security.oauth2.provider.client.ClientDetailsUserDetailsService
import org.springframework.security.oauth2.provider.code.AuthorizationCodeTokenGranter
import org.springframework.security.oauth2.provider.endpoint.DefaultRedirectResolver
import org.springframework.security.oauth2.provider.endpoint.FrameworkEndpointHandlerMapping
import org.springframework.security.oauth2.provider.error.OAuth2AuthenticationEntryPoint
import org.springframework.security.oauth2.provider.expression.OAuth2WebSecurityExpressionHandler
import org.springframework.security.oauth2.provider.implicit.ImplicitTokenGranter
import org.springframework.security.oauth2.provider.password.ResourceOwnerPasswordTokenGranter
import org.springframework.security.oauth2.provider.refresh.RefreshTokenGranter
import org.springframework.security.oauth2.provider.token.DefaultTokenServices
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.context.SecurityContextPersistenceFilter
import org.springframework.security.web.util.AntPathRequestMatcher
import org.springframework.security.web.util.RequestMatcher
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter

import javax.servlet.Filter

class SpringSecurityOauth2ProviderGrailsPlugin {
	static final Logger log = LoggerFactory.getLogger(this)

	def version = "1.0.5-SNAPSHOT"
	String grailsVersion = '2.0 > *'

	List pluginExcludes = [
		'docs/**',
		'src/docs/**',
		'examples/**',
		// Domains
		'test/**',
		// Controllers
		'grails-app/controllers/**',
		'grails-app/domain/test/**',
		'grails-app/i18n/**',
		// Views
		'web-app/**',
		'grails-app/views/login/**',
		'grails-app/views/secured/**',
		'grails-app/views/index.gsp',
		'grails-app/views/error.gsp',
	]

	//Map dependsOn = [springSecurityCore: '1.0 > *']
	def loadAfter = ["springSecurityCore"]

	def license = "APACHE"
	def organization = [ name:"Adaptive Computing", url:"http://adaptivecomputing.com" ]
	def issueManagement = [ system:"GitHub", url:"http://github.com/adaptivecomputing/grails-spring-security-oauth2-provider/issues" ]
	def scm = [ url:"http://github.com/adaptivecomputing/grails-spring-security-oauth2-provider" ]

	String author = 'Brian Saville'
	String authorEmail = 'bsaville@adaptivecomputing.com'
	String title = 'OAuth2 Provider support for the Spring Security plugin.'
	String description = '''\
OAuth2 Provider support for the Spring Security plugin.
'''

	String documentation = 'http://grails.org/plugin/spring-security-oauth2-provider'

	def doWithSpring = {
		def conf = SpringSecurityUtils.securityConfig
		if (!conf || !conf.active) {
			return
		}

		SpringSecurityUtils.loadSecondaryConfig 'DefaultOAuth2ProviderSecurityConfig'
		// have to get again after overlaying DefaultOAuthProviderSecurityConfig
		conf = SpringSecurityUtils.securityConfig

		if (!conf.oauthProvider.active)
			return

		println 'Configuring Spring Security OAuth2 provider ...'

        /* Gorm backed beans */
        springConfig.addAlias 'clientDetailsService', 'gormClientDetailsService'
        springConfig.addAlias 'tokenStore', 'gormTokenStoreService'
        springConfig.addAlias 'authorizationCodeServices', 'gormAuthorizationCodeService'

        /* Helper classes for Gorm support */
        oAuth2AuthenticationSerializer(OAuth2AuthenticationSerializer)
        authorizationRequestHolderSerializer(AuthorizationRequestHolderSerializer)

		tokenServices(DefaultTokenServices) {
			tokenStore = ref("tokenStore")
			clientDetailsService = ref("clientDetailsService")
			accessTokenValiditySeconds = conf.oauthProvider.tokenServices.accessTokenValiditySeconds
			refreshTokenValiditySeconds = conf.oauthProvider.tokenServices.refreshTokenValiditySeconds
			reuseRefreshToken = conf.oauthProvider.tokenServices.reuseRefreshToken
			supportRefreshToken = conf.oauthProvider.tokenServices.supportRefreshToken
		}

        userApprovalHandler(TokenServicesUserApprovalHandler) {
			approvalParameter = conf.oauthProvider.userApprovalParameter
			tokenServices = ref("tokenServices")
		}

        /* Register authorization request manager */
        oauth2AuthorizationRequestManager(DefaultAuthorizationRequestManager, ref('clientDetailsService'))

        /* Register token granters */
        def grantTypes = conf.oauthProvider.grantTypes
        def availableGranters = []

        /* authorization-code */
        if(grantTypes.authorizationCode) {
            authorizationCodeTokenGranter(AuthorizationCodeTokenGranter,
                    ref('tokenServices'), ref('authorizationCodeServices'), ref('clientDetailsService'))
            availableGranters << ref('authorizationCodeTokenGranter')
        }

        /* refresh-token */
        if(grantTypes.refreshToken) {
            refreshTokenGranter(RefreshTokenGranter, ref('tokenServices'), ref('clientDetailsService'))
            availableGranters << ref('refreshTokenGranter')
        }

        /* implicit */
        if(grantTypes.implicit) {
            implicitGranter(ImplicitTokenGranter, ref('tokenServices'), ref('clientDetailsService'))
            availableGranters << ref('implicitGranter')
        }

        /* client-credentials */
        if(grantTypes.clientCredentials) {
            clientCredentialsGranter(ClientCredentialsTokenGranter, ref('tokenServices'), ref('clientDetailsService'))
            availableGranters << ref('clientCredentialsGranter')
        }

        /* password */
        if(grantTypes.password) {
            resourceOwnerPasswordGranter(ResourceOwnerPasswordTokenGranter,
                ref('authenticationManager'), ref('tokenServices'), ref('clientDetailsService'))
            availableGranters << ref('resourceOwnerPasswordGranter')
        }

        oauth2TokenGranter(CompositeTokenGranter, availableGranters)

        if(conf.oauthProvider.authorization.requireRegisteredRedirectUri) {
            /* Require clients to have registered redirect URIs */
            redirectResolver(RequiredRedirectResolver)
        }
        else {
            /* This resolver will use the requested redirect URI if client does not have one registered */
            redirectResolver(DefaultRedirectResolver)
        }

        /* Register authorization endpoint */
        oauth2AuthorizationEndpoint(WrappedAuthorizationEndpoint) {
            tokenGranter = ref('oauth2TokenGranter')
            authorizationCodeServices = ref('authorizationCodeServices')
            clientDetailsService = ref('clientDetailsService')
            redirectResolver = ref('redirectResolver')
            userApprovalHandler = ref('userApprovalHandler')
            userApprovalPage = conf.oauthProvider.userApprovalEndpointUrl
            errorPage = conf.oauthProvider.errorEndpointUrl
        }

        /* Register token endpoint */
        oauth2TokenEndpoint(WrappedTokenEndpoint) {
            clientDetailsService = ref('clientDetailsService')
            tokenGranter = ref('oauth2TokenGranter')

        }

        /* Register handler mapping for token and authorization endpoints */
        oauth2HandlerMapping(FrameworkEndpointHandlerMapping) {
            mappings = [
                    "/oauth/token": conf.oauthProvider.tokenEndpointUrl,
                    "/oauth/authorize": conf.oauthProvider.authorizationEndpointUrl
            ]
        }

		// Allow client log-ins
		clientDetailsUserService(ClientDetailsUserDetailsService, ref('clientDetailsService'))
		clientCredentialsAuthenticationProvider(DaoAuthenticationProvider) {
			userDetailsService = ref('clientDetailsUserService')
		}
		clientCredentialsTokenEndpointFilter(ClientCredentialsTokenEndpointFilter) {
			authenticationManager = ref('authenticationManager')
		}

		// Register jackson handler for token responses
		annotationHandlerAdapter(RequestMappingHandlerAdapter){
			messageConverters = [
					new StringHttpMessageConverter(writeAcceptCharset: false),
					new ByteArrayHttpMessageConverter(),
					new FormHttpMessageConverter(),
					new SourceHttpMessageConverter(),
					new MappingJacksonHttpMessageConverter()
			]
		}

        // Configure multiple authentication entry points
        // http://jdevdiary.blogspot.com/2013/03/grails-spring-security-and-multiple.html
        oAuth2RequestMatcher(AntPathRequestMatcher, conf.oauthProvider.tokenEndpointUrl + '**')
        oAuth2AuthenticationEntryPoint(OAuth2AuthenticationEntryPoint)

        Map<RequestMatcher, AuthenticationEntryPoint> authenticationEntryPointMap = [
                (oAuth2RequestMatcher): oAuth2AuthenticationEntryPoint
        ]

        // This is identical to the authenticationEntryPoint bean configured by core plugin
        defaultAuthenticationEntryPoint(AjaxAwareAuthenticationEntryPoint, conf.auth.loginFormUrl) {
            ajaxLoginFormUrl = conf.auth.ajaxLoginFormUrl
            forceHttps = conf.auth.forceHttps
            useForward = conf.auth.useForward
            portMapper = ref('portMapper')
            portResolver = ref('portResolver')
        }

        authenticationEntryPoint(DelegatingAuthenticationEntryPoint, authenticationEntryPointMap) {
            defaultEntryPoint = ref('defaultAuthenticationEntryPoint')
        }

        // Override expression handler provided by Spring Security core plugin
        // TODO: See if there is a more stable way to do this, e.g. config option
        webExpressionHandler(OAuth2WebSecurityExpressionHandler) {
            roleHierarchy = ref('roleHierarchy')
            expressionParser = ref('voterExpressionParser')
            permissionEvaluator = ref('permissionEvaluator')
        }

        // Register the token endpoint as stateless
        // This is added to the filter chain
        nullContextRepository(NullSecurityContextRepository)
        statelessSecurityContextPersistenceFilter(SecurityContextPersistenceFilter, ref('nullContextRepository'))

		// Register endpoint URL filter since we define the URLs above
		SpringSecurityUtils.registerFilter 'clientCredentialsTokenEndpointFilter',
				conf.oauthProvider.clientFilterStartPosition + 1

		println "... done configuring Spring Security OAuth2 provider"
	}

    def doWithApplicationContext = { ctx ->
        def conf = SpringSecurityUtils.securityConfig
        if(conf.oauthProvider.tokenEndpointFilterChain.disabled) {
            log.debug("Skipping token endpoint filter chain configuration")
            return
        }

        def springSecurityFilterChain = ctx.springSecurityFilterChain
        def originalFilterChainMap = springSecurityFilterChain.filterChainMap

        def tokenEndpointUrl =  conf.oauthProvider.tokenEndpointUrl
        def statelessUrlPattern = conf.oauthProvider.tokenEndpointFilterChain.baseUrlPattern

        // Inherit the filter chain specified by another end point
        def allFilters = findFilterChainForUrl(statelessUrlPattern, originalFilterChainMap).value as List
        if(allFilters == null) {
            log.warn("Could not find base filter chain for pattern [${statelessUrlPattern}] to use for token endpoint [${tokenEndpointUrl}]")
            return
        }

        // Locate the securityContextPersistenceFilter bean to replace
        def scpfIdx = allFilters.findIndexOf { it instanceof SecurityContextPersistenceFilter }
        def scpfBean = ctx.getBean('securityContextPersistenceFilter')

        // Skip if the securityContextPersistenceFilter is not present
        def filterPresent = (scpfIdx != -1) && allFilters[scpfIdx].is(scpfBean)
        if(filterPresent) {
            // Replace default securityContextPersistenceFilter bean with one that is stateless
            def tokenEndpointFilters = replaceSecurityContextPersistenceFilterWithStateless(allFilters, scpfIdx, ctx)

            // Rebuild the filterChainMap with the the token endpoint filter at the beginning
            def filterChainMap = injectFilterChain(tokenEndpointUrl, tokenEndpointFilters, originalFilterChainMap)
            springSecurityFilterChain.filterChainMap = filterChainMap
        }
    }

    private Map injectFilterChain(String url, List filters, Map oldFilterChainMap) {
        Map<RequestMatcher, List<Filter>> filterChainMap = [:]
        filterChainMap[new AntPathRequestMatcher(url)] = filters
        filterChainMap << oldFilterChainMap
        return filterChainMap
    }

    private List replaceSecurityContextPersistenceFilterWithStateless(List allFilters, int scpfIdx, ApplicationContext ctx) {
        def tokenEndpointFilters = []
        allFilters.eachWithIndex { filter, idx ->
            if (idx == scpfIdx) {
                def statelessFilter = ctx.getBean('statelessSecurityContextPersistenceFilter')
                tokenEndpointFilters << statelessFilter
            } else {
                tokenEndpointFilters << filter
            }

        }
        return tokenEndpointFilters
    }

    private def findFilterChainForUrl(String url, Map filterChainMap) {
        filterChainMap.find { AntPathRequestMatcher urlPattern, filters ->
            urlPattern.pattern == url
        }
    }
}


package rop.impl;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.validation.SmartValidator;

import rop.*;
import rop.annotation.HttpAction;
import rop.annotation.ParamValid;
import rop.converter.Complex;
import rop.converter.ConverterContainer;
import rop.converter.RopConverter;
import rop.converter.Style;
import rop.request.ServiceRequest;
import rop.request.SystemParameterNames;
import rop.session.SessionManager;
import rop.thirdparty.com.alibaba.fastjson.JSON;
import rop.utils.RopUtils;
import rop.utils.spring.AnnotationUtils;

import javax.servlet.http.HttpServletRequest;

import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * <pre>
 *     请求上下文Builder
 * </pre>
 *
 * @author 陈雄华
 * @author luopeng
 * @version 1.0
 */
public class ServletRequestContextBuilder implements RequestContextBuilder {

	//通过前端的负载均衡服务器时，请求对象中的IP会变成负载均衡服务器的IP，因此需要特殊处理，下同。
	public static final String X_REAL_IP = "X-Real-IP";

	public static final String X_FORWARDED_FOR = "X-Forwarded-For";

	protected final Logger logger = LoggerFactory.getLogger(getClass());

	private ConverterContainer converterContainer;

	private SmartValidator validator;

	private SessionManager sessionManager;

	public ServletRequestContextBuilder(ConverterContainer converterContainer, SessionManager sessionManager) {
		this.converterContainer = converterContainer;
		this.sessionManager = sessionManager;
	}

	@Override
	public SimpleRopRequestContext buildSystemParams(RopContext ropContext, Object request) {
		if (!(request instanceof HttpServletRequest)) {
			throw new IllegalArgumentException("请求对象必须是HttpServletRequest的类型");
		}

		HttpServletRequest servletRequest = (HttpServletRequest) request;
		SimpleRopRequestContext requestContext = new SimpleRopRequestContext(ropContext);

		//设置请求对象及参数列表
		requestContext.setRawRequestObject(servletRequest);
		requestContext.setRequestId(getRopRequestId(servletRequest));

		requestContext.setIp(getRemoteAddr(servletRequest)); //感谢melin所指出的BUG

		//设置服务的系统级参数
		resolveHeaders(servletRequest, requestContext);

		//处理Content-Type为multipart情况
		if (isMultipartRequest(servletRequest)) {
			buildBusinessParamsMultipart(requestContext, servletRequest);
		} else {
			requestContext.setRequestBodyMap(getRequestParams(servletRequest));
		}

		//设置服务处理器
		ServiceMethodHandler serviceMethodHandler =
				ropContext.getServiceMethodHandler(requestContext.getMethod(), requestContext.getVersion());
		requestContext.setServiceMethodHandler(serviceMethodHandler);

		return requestContext;
	}

	public static String getRopRequestId(HttpServletRequest request){
		return (String)request.getAttribute(AnnotationServletServiceRouter.ROP_REQUEST_ID);
	}

	private void resolveHeaders(HttpServletRequest servletRequest, SimpleRopRequestContext requestContext) {
		String appKey = servletRequest.getHeader(SystemParameterNames.getAppKey());
		String sessionId = servletRequest.getHeader(SystemParameterNames.getSessionId());
		String method = servletRequest.getHeader(SystemParameterNames.getMethod());
		String version = servletRequest.getHeader(SystemParameterNames.getVersion());
		String sign = servletRequest.getHeader(SystemParameterNames.getSign());
		String timestamp = servletRequest.getHeader(SystemParameterNames.getTimestamp());
		String format = servletRequest.getHeader(SystemParameterNames.getFormat());
		String locale = servletRequest.getHeader(SystemParameterNames.getLocale());
		Map<String,String> extInfoMap = resolveExt(servletRequest,requestContext);

		requestContext.setExtInfoMap(extInfoMap);

		Map<String, String> headerMap = new HashMap<String, String>();

		headerMap.put(SystemParameterNames.getAppKey(), appKey);
		requestContext.setAppKey(appKey);

		if (sessionId != null) {
			headerMap.put(SystemParameterNames.getSessionId(), sessionId);
			requestContext.setSessionId(sessionId);
		}

		headerMap.put(SystemParameterNames.getMethod(), method);
		requestContext.setMethod(method);

		headerMap.put(SystemParameterNames.getVersion(), version);
		requestContext.setVersion(version);

		headerMap.put(SystemParameterNames.getSign(), sign);
		requestContext.setSign(sign);

		headerMap.put(SystemParameterNames.getTimestamp(), timestamp);
		requestContext.setTimestamp(getTimestamp(servletRequest));

		if(format != null){
			headerMap.put(SystemParameterNames.getFormat(), format);
			requestContext.setFormat(format);
		}

		if(locale != null){
			headerMap.put(SystemParameterNames.getLocale(), locale);
			requestContext.setLocale(getLocale(servletRequest));
		}

		requestContext.setHttpAction(HttpAction.fromValue(servletRequest.getMethod()));

		requestContext.setRequestHeaderMap(headerMap);
	}

	private Map<String, String> resolveExt(HttpServletRequest servletRequest, SimpleRopRequestContext requestContext) {
		String extInfoStr = getExtInfo(servletRequest);
		return RopUtils.decryptExtInfo(extInfoStr);
	}

	private boolean isMultipartRequest(HttpServletRequest servletRequest) {
		return servletRequest.getContentType() != null && servletRequest.getContentType().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
	}

	/**
	 * 处理文件上传绑定取值
	 *
	 * @param requestContext
	 * @param servletRequest
	 */
	private void buildBusinessParamsMultipart(SimpleRopRequestContext requestContext, HttpServletRequest servletRequest) {
		ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());
		upload.setHeaderEncoding(Constants.UTF8);
		try {
			List<FileItem> items = upload.parseRequest(servletRequest);
			requestContext.setFileItems(items);

			HashMap<String, String> destParamMap = new HashMap<String, String>(items.size());
			for (FileItem item : items) {
				String fieldName = item.getFieldName();
				String fieldValue = item.getString(Constants.UTF8);
				destParamMap.put(fieldName, fieldValue);
			}
			requestContext.setRequestBodyMap(destParamMap);
		} catch (FileUploadException e) {
			throw new RuntimeException(e);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	private String getRemoteAddr(HttpServletRequest request) {
		String remoteIp = request.getHeader(X_REAL_IP); //nginx反向代理
		if (StringUtils.isNotBlank(remoteIp)) {
			return remoteIp;
		} else {
			remoteIp = request.getHeader(X_FORWARDED_FOR);//apache反射代理
			if (StringUtils.isNotBlank(remoteIp)) {
				String[] ips = remoteIp.split(",");
				for (String ip : ips) {
					if (!"null".equalsIgnoreCase(ip)) {
						return ip;
					}
				}
			}
			return request.getRemoteAddr();
		}
	}

	/**
	 * 将{@link javax.servlet.http.HttpServletRequest}的数据绑定到{@link rop.RopRequestContext}的{@link rop.request.ServiceRequest}中，同时使用
	 * JSR 303对请求数据进行校验，将错误信息设置到{@link rop.RopRequestContext}的属性列表中。
	 *
	 * @param ropRequestContext
	 */
	@Override
	public void bindBusinessParams(RopRequestContext ropRequestContext) {

		Class<?>[] paramTypes = ropRequestContext.getServiceMethodHandler().getMethodParameterTypes();
		Object[] params = new Object[paramTypes.length];
		List<ObjectError> allErrors = new LinkedList<ObjectError>();
		for (int i = 0; i < paramTypes.length; ++i) {
			Class<?> paramType = paramTypes[i];
			if (ClassUtils.isAssignable(RopRequestContext.class, paramType)) {
				params[i] = ropRequestContext;
			} else if (ClassUtils.isAssignable(ServiceRequest.class, paramType)) {
				HttpServletRequest request =
						(HttpServletRequest) ropRequestContext.getRawRequestObject();
				BindingResult bindingResult = doBind(request, ropRequestContext, paramType, i);
				params[i] = bindingResult.getTarget();
				allErrors.addAll(bindingResult.getAllErrors());
			} else {
				throw new RopException("Unsupport bind class:" + paramType.getCanonicalName());
			}
		}
		ropRequestContext.setAttribute(SimpleRopRequestContext.SPRING_VALIDATE_ERROR_ATTRNAME, allErrors);
		ropRequestContext.setServiceMethodParameters(params);
	}

	private long getTimestamp(HttpServletRequest servletRequest) {
		String timestamp = servletRequest.getHeader(SystemParameterNames.getTimestamp());
		return getTimestamp(timestamp);
	}

	private long getTimestamp(String timestamp) {
		try {
			return Long.parseLong(timestamp);
		} catch (Exception e) {
			return 0L;
		}
	}

	public static String getHeader(HttpServletRequest request,String headerName){
		return request.getHeader(headerName);
	}

	public static String getMethod(HttpServletRequest request){
		return getHeader(request,SystemParameterNames.getMethod());
	}

	public static String getJsonpCallback(HttpServletRequest request){
		return getHeader(request,SystemParameterNames.getJsonp());
	}

	public static String getVersion(HttpServletRequest request){
		return getHeader(request,SystemParameterNames.getVersion());
	}

	public static String getExtInfo(HttpServletRequest request){
		return getHeader(request,SystemParameterNames.getExtInfo());
	}

	public static Locale getLocale(HttpServletRequest webRequest) {
		return getLocale(webRequest.getHeader(SystemParameterNames.getLocale()));
	}

	public static Locale getLocale(String locale) {
		if (StringUtils.isNotEmpty(locale)) {
			return RopUtils.getLocale(locale);
		}
		return Locale.SIMPLIFIED_CHINESE;
	}

	private HashMap<String, String> getRequestParams(HttpServletRequest request) {
		Map srcParamMap = request.getParameterMap();
		HashMap<String, String> destParamMap = new HashMap<String, String>(srcParamMap.size());
		Iterator<Map.Entry> it = srcParamMap.entrySet().iterator();
		while(it.hasNext()){
			Map.Entry entry = it.next();
			String[] values = (String[]) srcParamMap.get(entry.getKey());
			if (values != null && values.length > 0) {
				destParamMap.put((String) entry.getKey(), values[0]);
			} else {
				destParamMap.put((String) entry.getKey(), null);
			}
		}
		return destParamMap;
	}


	private BindingResult doBind(HttpServletRequest webRequest, final RopRequestContext ropRequestContext, Class<?> classType, int index) {

		final Map<String, String> requestBodyMap = ropRequestContext.getRequestBodyMap();

		final Map<String, Object> newRequestBodyMap = new HashMap<String, Object>();
		for (Map.Entry<String, String> entry : requestBodyMap.entrySet()) {
			newRequestBodyMap.put(entry.getKey(), entry.getValue());
		}

		//处理转换
		ReflectionUtils.doWithFields(classType,new ReflectionUtils.FieldCallback() {
			@Override
			public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
				if (converterContainer.support(field.getType()) && requestBodyMap.containsKey(field.getName())) {
					RopConverter converter = converterContainer.getConverter(field.getType());
					newRequestBodyMap.put(field.getName(), converter.convertToObject(requestBodyMap.get(field.getName())));
				}else if(Style.JSON.equals(getStyle(field))){
					newRequestBodyMap.put(field.getName(), JSON.parseObject(requestBodyMap.get(field.getName()),field.getType()));
				}
			}
		});

		//作转换,效率可能存在一定问题
		final Object bindObject = BeanUtils.instantiateClass(classType);
		try {
			org.apache.commons.beanutils.BeanUtils.populate(bindObject, newRequestBodyMap);
		} catch (IllegalAccessException e) {
			throw new RopException("doBind error", e);
		} catch (InvocationTargetException e) {
			throw new RopException("doBind error", e);
		}
		BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(bindObject, "bindObject");

		//服务方法参数注解
		String[] validateProfiles = null;
		Annotation[][] parameterAnnotations = ropRequestContext.getServiceMethodDefinition().getMethodParameterAnnotaions();
		Annotation[] parameterAnnotaion = parameterAnnotations[index];
		for (Annotation annotation : parameterAnnotaion) {
			if (annotation instanceof ParamValid) {
				validateProfiles = ((ParamValid) annotation).profiles();
			}
		}
		if (validateProfiles == null) {
			validator.validate(bindObject, bindingResult);
		} else {
			validator.validate(bindObject, bindingResult, validateProfiles);
		}

		return bindingResult;

	}

	private Style getStyle(Field field){
		Complex complex = AnnotationUtils.getAnnotation(field, Complex.class);
		if(complex != null){
			return complex.style();
		}
		return null;
	}

	public void setValidator(SmartValidator validator) {
		this.validator = validator;
	}

	//默认的{@link ServiceRequest}实现类
	private static class DefaultServiceRequest implements ServiceRequest {
	}
}


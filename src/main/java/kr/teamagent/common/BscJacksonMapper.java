package kr.teamagent.common;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.view.json.MappingJackson2JsonView;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.JsonNullSerializer;

/**
 * 전체를 오버라이딩 한 이유는 protected 메쏘드 때문에 일부분만 오버라이딩 해서는 동작이 정상적으로 되지 않아서
 * 그냥 전체를 오버라이딩 함..
 * by 윤태성
 */
public class BscJacksonMapper extends MappingJackson2JsonView {

	/**
	 * Default content type. Overridable as bean property.
	 */
	public static final String DEFAULT_CONTENT_TYPE = "application/json";

	private ObjectMapper objectMapper = new ObjectMapper();

	private JsonEncoding encoding = JsonEncoding.UTF8;

    private String jsonPrefix;

    private Boolean prettyPrint;

	private final boolean prefixJson = false;

	private Set<String> renderedAttributes;

	private Set<String> modelKeys;

    private boolean extractValueFromSingleKeyModel = false;

    private boolean disableCaching = true;

    private boolean updateContentLength = false;

	/**
	 * Construct a new {@code JacksonJsonView}, setting the content type to {@code application/json}.
	 */
    public BscJacksonMapper() {
		setContentType(DEFAULT_CONTENT_TYPE);
		// <,>,&,\," 등을 html 특수문자로 바꿔주기 위해서 추가함.
		//objectMapper.getJsonFactory().setCharacterEscapes(new HTMLCharacterEscapes());
		// 모든 null 값을 ""(공백)으로 처리함. 이걸 쓰지 않으면 공백은 "필드명" : "" , null은 json에서 "필드명" : null
		objectMapper.getSerializerProvider().setNullValueSerializer(new JsonNullSerializer());
	}

	/**
	 * Set the {@code ObjectMapper} for this view.
	 * If not set, a default {@link ObjectMapper#ObjectMapper() ObjectMapper} will be used.
	 * <p>Setting a custom-configured {@code ObjectMapper} is one way to take further control of
	 * the JSON serialization process. The other option is to use Jackson's provided annotations
	 * on the types to be serialized, in which case a custom-configured ObjectMapper is unnecessary.
	 */
	public void setObjectMapper(ObjectMapper objectMapper) {
		//Assert.notNull(objectMapper, "'objectMapper' must not be null");
		this.objectMapper = objectMapper;
		configurePrettyPrint();
	}


	/**
	 * Sets the {@code JsonEncoding} for this converter. By default, {@linkplain JsonEncoding#UTF8 UTF-8} is used.
	 */
	public void setEncoding(JsonEncoding encoding) {
		//Assert.notNull(encoding, "'encoding' must not be null");
		this.encoding = encoding;
	}

    /**
     * Specify a custom prefix to use for this view's JSON output.
     * Default is none.
     * @see #setPrefixJson
     */
    public void setJsonPrefix(String jsonPrefix) {
        this.jsonPrefix = jsonPrefix;
    }


	/**
     * Indicates whether the JSON output by this view should be prefixed with <tt>"{} && "</tt>.
     * Default is {@code false}.
     * <p>Prefixing the JSON string in this manner is used to help prevent JSON Hijacking.
     * The prefix renders the string syntactically invalid as a script so that it cannot be hijacked.
     * This prefix does not affect the evaluation of JSON, but if JSON validation is performed
     * on the string, the prefix would need to be ignored.
     * @see #setJsonPrefix
     */
    public void setPrefixJson(boolean prefixJson) {
        this.jsonPrefix = (prefixJson ? "{} && " : null);
    }


    /**
     * Whether to use the default pretty printer when writing JSON.
     * This is a shortcut for setting up an {@code ObjectMapper} as follows:
     * <pre class="code">
     * ObjectMapper mapper = new ObjectMapper();
     * mapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, true);
     * </pre>
     * <p>The default value is {@code false}.
     */
    public void setPrettyPrint(boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
        configurePrettyPrint();
    }

    private void configurePrettyPrint() {
        if (this.prettyPrint != null) {
            this.objectMapper.configure(SerializationFeature.INDENT_OUTPUT, this.prettyPrint);
        }
    }

    /**
     * Set the attribute in the model that should be rendered by this view.
     * When set, all other model attributes will be ignored.
     */
    public void setModelKey(String modelKey) {
        this.modelKeys = Collections.singleton(modelKey);
    }

    /**
     * Set the attributes in the model that should be rendered by this view.
     * When set, all other model attributes will be ignored.
     */
    public void setModelKeys(Set<String> modelKeys) {
        this.modelKeys = modelKeys;
    }

    /**
     * Set whether to serialize models containing a single attribute as a map or whether to
     * extract the single value from the model and serialize it directly.
     * <p>The effect of setting this flag is similar to using {@code MappingJacksonHttpMessageConverter}
     * with an {@code @ResponseBody} request-handling method.
     * <p>Default is {@code false}.
     */
    public void setExtractValueFromSingleKeyModel(boolean extractValueFromSingleKeyModel) {
        this.extractValueFromSingleKeyModel = extractValueFromSingleKeyModel;
    }

    /**
     * Disables caching of the generated JSON.
     * <p>Default is {@code true}, which will prevent the client from caching the generated JSON.
     */
    public void setDisableCaching(boolean disableCaching) {
        this.disableCaching = disableCaching;
    }

    /**
     * Whether to update the 'Content-Length' header of the response. When set to
     * {@code true}, the response is buffered in order to determine the content
     * length and set the 'Content-Length' header of the response.
     * <p>The default setting is {@code false}.
     */
    public void setUpdateContentLength(boolean updateContentLength) {
        this.updateContentLength = updateContentLength;
    }


	@Override
	protected void prepareResponse(HttpServletRequest request, HttpServletResponse response) {
		response.setContentType(getContentType());
		response.setCharacterEncoding(encoding.getJavaName());
		if (disableCaching) {
			response.addHeader("Pragma", "no-cache");
			response.addHeader("Cache-Control", "no-cache, no-store, max-age=0");
			response.addDateHeader("Expires", 1L);
		}
	}

	@Override
	protected void renderMergedOutputModel(Map<String, Object> model,
			HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		Object value = filterModel(model);
		JsonGenerator generator =
				objectMapper.getFactory().createGenerator(response.getOutputStream(), encoding);
		if (prefixJson) {
			generator.writeRaw("{} && ");
		}
		objectMapper.writeValue(generator, value);
	}

	/**
	 * Filters out undesired attributes from the given model. The return value can be either another {@link Map}, or a
	 * single value object.
	 *
	 * <p>Default implementation removes {@link BindingResult} instances and entries not included in the {@link
	 * #setRenderedAttributes(Set) renderedAttributes} property.
	 *
	 * @param model the model, as passed on to {@link #renderMergedOutputModel}
	 * @return the object to be rendered
	 */
	protected Object filterModel(Map<String, Object> model) {
		Map<String, Object> result = new HashMap<String, Object>(model.size());
		Set<String> renderedAttributes =
				!CollectionUtils.isEmpty(this.renderedAttributes) ? this.renderedAttributes : model.keySet();
		for (Map.Entry<String, Object> entry : model.entrySet()) {
			/*
			 * 20171211 kimyh 
			 * - key값이 searchVOO인 값은 json 파싱 대상에서 제외함.
			 * 	- 조회시의 파라미터가 json 리턴 결과에 노출되는 것을 방지하기 위함
			 * 	- 문제가 있을 경우 key 값을 바꾸거나 아래 소스를 삭제할 것.
			 * 	- dataVO는 조회할 때 사용할 수 있으니 예외로 둬야하나?
			 */
			if(CommonUtil.removeNull(entry.getKey()).equals("searchVO")) continue;
			
			if (!(entry.getValue() instanceof BindingResult) && renderedAttributes.contains(entry.getKey())) {
				result.put(entry.getKey(), entry.getValue());
			}
		}
		return result;
	}

	   /**
     * Write the actual JSON content to the stream.
     * @param stream the output stream to use
     * @param value the value to be rendered, as returned from {@link #filterModel}
     * @param jsonPrefix the prefix for this view's JSON output
     * (as indicated through {@link #setJsonPrefix}/{@link #setPrefixJson})
     * @throws IOException if writing failed
     */
    protected void writeContent(OutputStream stream, Object value, String jsonPrefix) throws IOException {
        JsonGenerator generator = this.objectMapper.getFactory().createGenerator(stream, this.encoding);

        // A workaround for JsonGenerators not applying serialization features
        // https://github.com/FasterXML/jackson-databind/issues/12
        if (this.objectMapper.getSerializationConfig().isEnabled(SerializationFeature.INDENT_OUTPUT)) {
            generator.useDefaultPrettyPrinter();
        }

        if (jsonPrefix != null) {
            generator.writeRaw(jsonPrefix);
        }
        this.objectMapper.writeValue(generator, value);
    }


}

package kr.teamagent.common.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.jasypt.commons.CommonUtils;
import org.jasypt.encryption.StringEncryptor;
import org.jasypt.properties.PropertyValueEncryptionUtils;
import org.jasypt.util.text.TextEncryptor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;

/*public class PropertyUtil extends PropertyPlaceholderConfigurer {
	private static Map<String, String> propertiesMap;
	// Default as in PropertyPlaceholderConfigurer
	private int springSystemPropertiesMode = SYSTEM_PROPERTIES_MODE_FALLBACK;

	@Override
	public void setSystemPropertiesMode(int systemPropertiesMode) {
		super.setSystemPropertiesMode(systemPropertiesMode);
		springSystemPropertiesMode = systemPropertiesMode;
	}

	@Override
	protected void processProperties(ConfigurableListableBeanFactory beanFactory, Properties props) throws BeansException {
		super.processProperties(beanFactory, props);

		propertiesMap = new HashMap<String, String>();
		for (Object key : props.keySet()) {
			String keyStr = key.toString();
			String valueStr = resolvePlaceholder(keyStr, props, springSystemPropertiesMode);
			propertiesMap.put(keyStr, valueStr);
		}
	}

	public static String getProperty(String name) {
		try {
			return propertiesMap.get(name).toString().trim();
		} catch(NullPointerException npe) {
			return null;
		} catch(Exception e) {
			return null;
		}
	}
}*/

public class PropertyUtil extends PropertyPlaceholderConfigurer {

	 /* Only one of these instances will be initialized, the other one will be
	 * null.
	 */

	private final StringEncryptor stringEncryptor;
	private final TextEncryptor textEncryptor;

	private static Map<String, String> propertiesMap;
	private static String key;
	/**
	 * <p>
	 * Creates an <tt>EncryptablePropertyPlaceholderConfigurer</tt> instance
	 * which will use the passed {@link StringEncryptor} object to decrypt
	 * encrypted values.
	 * </p>
	 *
	 * @param stringEncryptor
	 *            the {@link StringEncryptor} to be used do decrypt values. It
	 *            can not be null.
	 */
	public PropertyUtil(
	        final StringEncryptor stringEncryptor) {
		super();
		CommonUtils.validateNotNull(stringEncryptor, "Encryptor cannot be null");
		this.stringEncryptor = stringEncryptor;
		this.textEncryptor = null;
	}

	/**
	 * <p>
	 * Creates an <tt>EncryptablePropertyPlaceholderConfigurer</tt> instance which will use the
	 * passed {@link TextEncryptor} object to decrypt encrypted values.
	 * </p>
	 *
	 * @param textEncryptor
	 *            the {@link TextEncryptor} to be used do decrypt values. It can
	 *            not be null.
	 */
	public PropertyUtil(final TextEncryptor textEncryptor) {
		super();
		CommonUtils.validateNotNull(textEncryptor, "Encryptor cannot be null");
		this.stringEncryptor = null;
		this.textEncryptor = textEncryptor;
	}


	 /* (non-Javadoc)
	 *
	 * @see org.springframework.beans.factory.config.PropertyResourceConfigurer#convertPropertyValue(java.lang.String)
	 */
    @Override
	protected String convertPropertyValue(final String originalValue) {
		if (!PropertyValueEncryptionUtils.isEncryptedValue(originalValue)) {
			return originalValue;
		}
		if (this.stringEncryptor != null) {
			return PropertyValueEncryptionUtils.decrypt(originalValue,
					this.stringEncryptor);

		}
		return PropertyValueEncryptionUtils.decrypt(originalValue, this.textEncryptor);
	}


	 /* (non-Javadoc)
	 *
	 * @since 1.8
	 * @see org.springframework.beans.factory.config.PropertyPlaceholderConfigurer#resolveSystemProperty(java.lang.String)
	 */
    @Override
    protected String resolveSystemProperty(final String key) {
        return convertPropertyValue(super.resolveSystemProperty(key));
    }

    @Override
	protected void processProperties(ConfigurableListableBeanFactory beanFactory, Properties props) throws BeansException {
		super.processProperties(beanFactory, props);

		propertiesMap = new HashMap<String, String>();
		for (Object key : props.keySet()) {
			String keyStr = key.toString();
			String valueStr = resolvePlaceholder(keyStr, props, SYSTEM_PROPERTIES_MODE_OVERRIDE);
			propertiesMap.put(keyStr, convertPropertyValue(valueStr));
		}
	}

    public static String getProperty(String name) {
		try {
			return propertiesMap.get(name).trim();
		} catch(NullPointerException npe) {
			return null;
		} catch(Exception e) {
			return null;
		}
	}
}


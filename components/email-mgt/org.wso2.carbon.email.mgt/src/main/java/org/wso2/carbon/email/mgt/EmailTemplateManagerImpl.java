/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.email.mgt;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.email.mgt.constants.I18nMgtConstants;
import org.wso2.carbon.email.mgt.exceptions.DuplicateEmailTemplateException;
import org.wso2.carbon.email.mgt.exceptions.I18nEmailMgtClientException;
import org.wso2.carbon.email.mgt.exceptions.I18nEmailMgtException;
import org.wso2.carbon.email.mgt.exceptions.I18nEmailMgtInternalException;
import org.wso2.carbon.email.mgt.exceptions.I18nEmailMgtServerException;
import org.wso2.carbon.email.mgt.exceptions.I18nMgtEmailConfigException;
import org.wso2.carbon.email.mgt.internal.I18nMgtDataHolder;
import org.wso2.carbon.email.mgt.model.EmailTemplate;
import org.wso2.carbon.email.mgt.util.I18nEmailUtil;
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.base.IdentityValidationUtil;
import org.wso2.carbon.identity.core.persistence.registry.RegistryResourceMgtService;
import org.wso2.carbon.identity.governance.IdentityGovernanceUtil;
import org.wso2.carbon.identity.governance.IdentityMgtConstants;
import org.wso2.carbon.identity.governance.exceptions.notiification.NotificationTemplateManagerClientException;
import org.wso2.carbon.identity.governance.exceptions.notiification.NotificationTemplateManagerException;
import org.wso2.carbon.identity.governance.exceptions.notiification.NotificationTemplateManagerInternalException;
import org.wso2.carbon.identity.governance.exceptions.notiification.NotificationTemplateManagerServerException;
import org.wso2.carbon.identity.governance.model.NotificationTemplate;
import org.wso2.carbon.identity.governance.service.notification.NotificationChannels;
import org.wso2.carbon.identity.governance.service.notification.NotificationTemplateManager;
import org.wso2.carbon.registry.core.Collection;
import org.wso2.carbon.registry.core.RegistryConstants;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.ResourceImpl;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.utils.CarbonUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.DEFAULT_EMAIL_LOCALE;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.DEFAULT_SMS_NOTIFICATION_LOCALE;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.EMAIL_TEMPLATE_NAME;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.EMAIL_TEMPLATE_PATH;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.EMAIL_TEMPLATE_TYPE_DISPLAY_NAME;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.EMAIL_TEMPLATE_TYPE_REGEX;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.ErrorCodes.EMAIL_TEMPLATE_TYPE_NOT_FOUND;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.SMS_TEMPLATE_PATH;
import static org.wso2.carbon.identity.base.IdentityValidationUtil.ValidatorPattern.REGISTRY_INVALID_CHARS_EXISTS;
import static org.wso2.carbon.registry.core.RegistryConstants.PATH_SEPARATOR;

/**
 * Provides functionality to manage email templates used in notification emails.
 */
public class EmailTemplateManagerImpl implements EmailTemplateManager, NotificationTemplateManager {

    private I18nMgtDataHolder dataHolder = I18nMgtDataHolder.getInstance();
    private RegistryResourceMgtService resourceMgtService = dataHolder.getRegistryResourceMgtService();

    private static final Log log = LogFactory.getLog(EmailTemplateManagerImpl.class);

    private static final String TEMPLATE_REGEX_KEY = I18nMgtConstants.class.getName() + "_" + EMAIL_TEMPLATE_NAME;
    private static final String REGISTRY_INVALID_CHARS = I18nMgtConstants.class.getName() + "_" + "registryInvalidChar";

    static {
        IdentityValidationUtil.addPattern(TEMPLATE_REGEX_KEY, EMAIL_TEMPLATE_TYPE_REGEX);
        IdentityValidationUtil.addPattern(REGISTRY_INVALID_CHARS, REGISTRY_INVALID_CHARS_EXISTS.getRegex());
    }

    @Override
    public void addEmailTemplateType(String emailTemplateDisplayName, String tenantDomain) throws
            I18nEmailMgtException {

        try {
            addNotificationTemplateType(emailTemplateDisplayName, NotificationChannels.EMAIL_CHANNEL.getChannelType(),
                    tenantDomain);
        } catch (NotificationTemplateManagerClientException e) {
            throw new I18nEmailMgtClientException(e.getMessage(), e);
        } catch (NotificationTemplateManagerInternalException e) {
            if (StringUtils.isNotBlank(e.getErrorCode())) {
                String errorCode = e.getErrorCode();
                if (errorCode.contains(I18nMgtConstants.ErrorMessages.ERROR_CODE_DUPLICATE_TEMPLATE_TYPE.getCode())) {
                    throw new I18nEmailMgtInternalException(
                            I18nMgtConstants.ErrorCodes.EMAIL_TEMPLATE_TYPE_ALREADY_EXISTS, e.getMessage(), e);
                }
            }
            throw new I18nEmailMgtInternalException(e.getMessage(), e);
        } catch (NotificationTemplateManagerException e) {
            throw new I18nEmailMgtServerException(e.getMessage(), e);
        }
    }

    @Override
    public void addNotificationTemplateType(String displayName, String notificationChannel, String tenantDomain)
            throws NotificationTemplateManagerException {

        validateDisplayNameOfTemplateType(displayName);
        String normalizedDisplayName = I18nEmailUtil.getNormalizedName(displayName);

        // Persist the template type to registry ie. create a directory.
        String path = buildTemplateRootDirectoryPath(normalizedDisplayName, notificationChannel);
        try {
            // Check whether a template exists with the same name.
            if (resourceMgtService.isResourceExists(path, tenantDomain)) {
                String code = I18nEmailUtil.prependOperationScenarioToErrorCode(
                        I18nMgtConstants.ErrorMessages.ERROR_CODE_DUPLICATE_TEMPLATE_TYPE.getCode(),
                        I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
                String message =
                        String.format(I18nMgtConstants.ErrorMessages.ERROR_CODE_DUPLICATE_TEMPLATE_TYPE.getMessage(),
                                displayName, tenantDomain);
                throw new NotificationTemplateManagerInternalException(code, message);
            }
            Collection collection = I18nEmailUtil.createTemplateType(normalizedDisplayName, displayName);
            resourceMgtService.putIdentityResource(collection, path, tenantDomain);
        } catch (IdentityRuntimeException ex) {
            String code = I18nEmailUtil.prependOperationScenarioToErrorCode(
                    I18nMgtConstants.ErrorMessages.ERROR_CODE_ERROR_ADDING_TEMPLATE.getCode(),
                    I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
            String message =
                    String.format(I18nMgtConstants.ErrorMessages.ERROR_CODE_ERROR_ADDING_TEMPLATE.getMessage(),
                            displayName, tenantDomain);
            throw new NotificationTemplateManagerServerException(code, message);
        }
    }

    @Override
    public void deleteEmailTemplateType(String emailTemplateDisplayName, String tenantDomain) throws
            I18nEmailMgtException {

        validateTemplateType(emailTemplateDisplayName, tenantDomain);

        String templateType = I18nEmailUtil.getNormalizedName(emailTemplateDisplayName);
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + templateType;

        try {
            resourceMgtService.deleteIdentityResource(path, tenantDomain);
        } catch (IdentityRuntimeException ex) {
            String errorMsg = String.format
                    ("Error deleting email template type %s from %s tenant.", emailTemplateDisplayName, tenantDomain);
            handleServerException(errorMsg, ex);
        }
    }

    /**
     * @param tenantDomain
     * @return
     * @throws I18nEmailMgtServerException
     */
    @Override
    public List<String> getAvailableTemplateTypes(String tenantDomain) throws I18nEmailMgtServerException {

        try {
            List<String> templateTypeList = new ArrayList<>();
            Collection collection = (Collection) resourceMgtService.getIdentityResource(EMAIL_TEMPLATE_PATH,
                    tenantDomain);

            for (String templatePath : collection.getChildren()) {
                Resource templateTypeResource = resourceMgtService.getIdentityResource(templatePath, tenantDomain);
                if (templateTypeResource != null) {
                    String emailTemplateType = templateTypeResource.getProperty(EMAIL_TEMPLATE_TYPE_DISPLAY_NAME);
                    templateTypeList.add(emailTemplateType);
                }
            }
            return templateTypeList;
        } catch (IdentityRuntimeException | RegistryException ex) {
            String errorMsg = String.format("Error when retrieving email template types of %s tenant.", tenantDomain);
            throw new I18nEmailMgtServerException(errorMsg, ex);
        }
    }

    @Override
    public List<EmailTemplate> getAllEmailTemplates(String tenantDomain) throws I18nEmailMgtException {

        List<EmailTemplate> templateList = new ArrayList<>();

        try {
            Collection baseDirectory = (Collection) resourceMgtService.getIdentityResource(EMAIL_TEMPLATE_PATH,
                    tenantDomain);

            if (baseDirectory != null) {
                for (String templateTypeDirectory : baseDirectory.getChildren()) {
                    templateList.addAll(
                            getAllTemplatesOfTemplateTypeFromRegistry(templateTypeDirectory, tenantDomain));
                }
            }
        } catch (RegistryException | IdentityRuntimeException e) {
            String error = String.format("Error when retrieving email templates of %s tenant.", tenantDomain);
            throw new I18nEmailMgtServerException(error, e);
        }

        return templateList;
    }

    @Override
    public EmailTemplate getEmailTemplate(String templateDisplayName, String locale, String tenantDomain)
            throws I18nEmailMgtException {

        try {
            NotificationTemplate notificationTemplate = getNotificationTemplate(
                    NotificationChannels.EMAIL_CHANNEL.getChannelType(), templateDisplayName, locale, tenantDomain);
            return buildEmailTemplate(notificationTemplate);
        } catch (NotificationTemplateManagerException exception) {
            String errorCode = exception.getErrorCode();
            String errorMsg = exception.getMessage();
            Throwable throwable = exception.getCause();

            // Match NotificationTemplateManagerExceptions with the existing I18nEmailMgtException error types.
            if (StringUtils.isNotEmpty(exception.getErrorCode())) {
                if (IdentityMgtConstants.ErrorMessages.ERROR_CODE_INVALID_NOTIFICATION_TEMPLATE.getCode()
                        .equals(errorCode) || IdentityMgtConstants.ErrorMessages.ERROR_CODE_NO_CONTENT_IN_TEMPLATE
                        .getCode().equals(errorCode) ||
                        I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_CHARACTERS_IN_TEMPLATE_NAME.getCode()
                                .equals(errorCode) ||
                        I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_CHARACTERS_IN_LOCALE
                                .getCode().equals(errorCode)) {
                    throw new I18nEmailMgtClientException(errorMsg, throwable);

                } else if (IdentityMgtConstants.ErrorMessages.ERROR_CODE_INVALID_EMAIL_TEMPLATE_CONTENT.getCode()
                        .equals(errorCode)) {
                    throw new I18nMgtEmailConfigException(errorMsg, throwable);
                } else if (IdentityMgtConstants.ErrorMessages.ERROR_CODE_NO_TEMPLATE_FOUND.getCode()
                        .equals(errorCode)) {
                    throw new I18nEmailMgtInternalException(I18nMgtConstants.ErrorCodes.EMAIL_TEMPLATE_TYPE_NODE_FOUND,
                            errorMsg, throwable);
                }
            }
            throw new I18nEmailMgtServerException(exception.getMessage(), exception.getCause());
        }
    }

    @Override
    public List<EmailTemplate> getEmailTemplateType(String templateDisplayName, String tenantDomain)
            throws I18nEmailMgtException {

        validateTemplateType(templateDisplayName, tenantDomain);

        String templateDirectory = I18nEmailUtil.getNormalizedName(templateDisplayName);
        String templateTypeRegistryPath = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + templateDirectory;

        try {
            return getAllTemplatesOfTemplateTypeFromRegistry(templateTypeRegistryPath, tenantDomain);
        } catch (RegistryException ex) {
            String error = "Error when retrieving '%s' template type from %s tenant registry.";
            throw new I18nEmailMgtServerException(String.format(error, templateDisplayName, tenantDomain), ex);
        }
    }

    /**
     * Build an Email Template object using SMS template data.
     *
     * @param notificationTemplate {@link
     *                             org.wso2.carbon.identity.governance.service.notification.NotificationTemplateManager}
     *                             object
     * @return {@link org.wso2.carbon.email.mgt.model.EmailTemplate} object
     */
    private EmailTemplate buildEmailTemplate(NotificationTemplate notificationTemplate) {

        // Build an email template using SMS template data.
        EmailTemplate emailTemplate = new EmailTemplate();
        emailTemplate.setTemplateDisplayName(notificationTemplate.getDisplayName());
        emailTemplate.setTemplateType(notificationTemplate.getType());
        emailTemplate.setLocale(notificationTemplate.getLocale());
        emailTemplate.setBody(notificationTemplate.getBody());
        emailTemplate.setSubject(notificationTemplate.getSubject());
        emailTemplate.setFooter(notificationTemplate.getFooter());
        emailTemplate.setEmailContentType(notificationTemplate.getContentType());
        return emailTemplate;
    }

    /**
     * Get default notification template locale for a given notification channel.
     *
     * @param notificationChannel Notification channel
     * @return Default locale
     */
    private String getDefaultNotificationLocale(String notificationChannel) {

        if (NotificationChannels.SMS_CHANNEL.getChannelType().equals(notificationChannel)) {
            return DEFAULT_SMS_NOTIFICATION_LOCALE;
        } else {
            return DEFAULT_EMAIL_LOCALE;
        }
    }

    /**
     * Return the notification template from the tenant registry which matches the given channel and template name.
     *
     * @param notificationChannel Notification Channel Name (Eg: SMS or EMAIL)
     * @param templateType        Type of the template
     * @param locale              Locale
     * @param tenantDomain        Tenant Domain
     * @return Return {@link org.wso2.carbon.identity.governance.model.NotificationTemplate} object
     * @throws NotificationTemplateManagerException Error getting the notification template
     */
    public NotificationTemplate getNotificationTemplate(String notificationChannel, String templateType, String locale,
            String tenantDomain) throws NotificationTemplateManagerException {

        // Resolve channel to either SMS or EMAIL.
        notificationChannel = resolveNotificationChannel(notificationChannel);
        validateTemplateLocale(locale);
        validateDisplayNameOfTemplateType(templateType);
        NotificationTemplate notificationTemplate = null;

        // Get notification template registry path.
        String path;
        if (NotificationChannels.SMS_CHANNEL.getChannelType().equals(notificationChannel)) {
            path = SMS_TEMPLATE_PATH + PATH_SEPARATOR + I18nEmailUtil.getNormalizedName(templateType);
        } else {
            path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + I18nEmailUtil.getNormalizedName(templateType);
        }

        // Get registry resource.
        try {
            Resource registryResource = resourceMgtService.getIdentityResource(path, tenantDomain, locale);
            if (registryResource != null) {
                notificationTemplate = getNotificationTemplate(registryResource, notificationChannel);
            }
        } catch (IdentityRuntimeException exception) {
            String error = String
                    .format(IdentityMgtConstants.ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TEMPLATE_FROM_REGISTRY
                            .getMessage(), templateType, locale, tenantDomain);
            throw new NotificationTemplateManagerServerException(
                    IdentityMgtConstants.ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_TEMPLATE_FROM_REGISTRY.getCode(),
                    error, exception);
        }

        // Handle not having the requested SMS template type in required locale for this tenantDomain.
        if (notificationTemplate == null) {
            String defaultLocale = getDefaultNotificationLocale(notificationChannel);
            if (StringUtils.equalsIgnoreCase(defaultLocale, locale)) {

                // Template is not available in the default locale. Therefore, breaking the flow at the consuming side
                // to avoid NPE.
                String error = String
                        .format(IdentityMgtConstants.ErrorMessages.ERROR_CODE_NO_TEMPLATE_FOUND.getMessage(),
                                templateType, locale, tenantDomain);
                throw new NotificationTemplateManagerServerException(
                        IdentityMgtConstants.ErrorMessages.ERROR_CODE_NO_TEMPLATE_FOUND.getCode(), error);
            } else {
                if (log.isDebugEnabled()) {
                    String message = String
                            .format("'%s' template in '%s' locale was not found in '%s' tenant. Trying to return the "
                                            + "template in default locale : '%s'", templateType, locale, tenantDomain,
                                    DEFAULT_SMS_NOTIFICATION_LOCALE);
                    log.debug(message);
                }
                // Try to get the template type in default locale.
                return getNotificationTemplate(notificationChannel, templateType, defaultLocale, tenantDomain);
            }
        }
        return notificationTemplate;
    }

    /**
     * Get the notification template from resource.
     *
     * @param templateResource    {@link org.wso2.carbon.registry.core.Resource} object
     * @param notificationChannel Notification channel
     * @return {@link org.wso2.carbon.identity.governance.model.NotificationTemplate} object
     * @throws NotificationTemplateManagerException Error getting the notification template
     */
    private NotificationTemplate getNotificationTemplate(Resource templateResource, String notificationChannel)
            throws NotificationTemplateManagerException {

        NotificationTemplate notificationTemplate = new NotificationTemplate();

        // Get template meta properties.
        String displayName = templateResource.getProperty(I18nMgtConstants.TEMPLATE_TYPE_DISPLAY_NAME);
        String type = templateResource.getProperty(I18nMgtConstants.TEMPLATE_TYPE);
        String locale = templateResource.getProperty(I18nMgtConstants.TEMPLATE_LOCALE);
        if (NotificationChannels.EMAIL_CHANNEL.getChannelType().equals(notificationChannel)) {
            String contentType = templateResource.getProperty(I18nMgtConstants.TEMPLATE_CONTENT_TYPE);

            // Setting UTF-8 for all the email templates as it supports many languages and is widely adopted.
            // There is little to no value addition making the charset configurable.
            if (contentType != null && !contentType.toLowerCase().contains(I18nEmailUtil.CHARSET_CONSTANT)) {
                contentType = contentType + "; " + I18nEmailUtil.CHARSET_UTF_8;
            }
            notificationTemplate.setContentType(contentType);
        }
        notificationTemplate.setDisplayName(displayName);
        notificationTemplate.setType(type);
        notificationTemplate.setLocale(locale);

        // Process template content.
        String[] templateContentElements = getTemplateElements(templateResource, notificationChannel, displayName,
                locale);
        if (NotificationChannels.SMS_CHANNEL.getChannelType().equals(notificationChannel)) {
            notificationTemplate.setBody(templateContentElements[0]);
        } else {
            notificationTemplate.setSubject(templateContentElements[0]);
            notificationTemplate.setBody(templateContentElements[1]);
            notificationTemplate.setFooter(templateContentElements[2]);
        }
        notificationTemplate.setNotificationChannel(notificationChannel);
        return notificationTemplate;
    }

    /**
     * Process template resource content and retrieve template elements.
     *
     * @param templateResource    Resource of the template
     * @param notificationChannel Notification channel
     * @param displayName         Display name of the template
     * @param locale              Locale of the template
     * @return Template content
     * @throws NotificationTemplateManagerException If an error occurred while getting the template content
     */
    private String[] getTemplateElements(Resource templateResource, String notificationChannel, String displayName,
            String locale) throws NotificationTemplateManagerException {

        try {
            Object content = templateResource.getContent();
            if (content != null) {
                byte[] templateContentArray = (byte[]) content;
                String templateContent = new String(templateContentArray, Charset.forName("UTF-8"));

                String[] templateContentElements;
                try {
                    templateContentElements = new Gson().fromJson(templateContent, String[].class);
                } catch (JsonSyntaxException exception) {
                    String error = String.format(IdentityMgtConstants.ErrorMessages.
                            ERROR_CODE_DESERIALIZING_TEMPLATE_FROM_TENANT_REGISTRY.getMessage(), displayName, locale);
                    throw new NotificationTemplateManagerServerException(IdentityMgtConstants.ErrorMessages.
                            ERROR_CODE_DESERIALIZING_TEMPLATE_FROM_TENANT_REGISTRY.getCode(), error, exception);
                }

                // Validate template content.
                if (NotificationChannels.SMS_CHANNEL.getChannelType().equals(notificationChannel)) {
                    if (templateContentElements == null || templateContentElements.length != 1) {
                        String errorMsg = String.format(IdentityMgtConstants.ErrorMessages.
                                ERROR_CODE_INVALID_SMS_TEMPLATE_CONTENT.getMessage(), displayName, locale);
                        throw new NotificationTemplateManagerServerException(IdentityMgtConstants.ErrorMessages.
                                ERROR_CODE_INVALID_SMS_TEMPLATE_CONTENT.getCode(), errorMsg);
                    }
                } else {
                    if (templateContentElements == null || templateContentElements.length != 3) {
                        String errorMsg = String.format(IdentityMgtConstants.ErrorMessages.
                                ERROR_CODE_INVALID_EMAIL_TEMPLATE_CONTENT.getMessage(), displayName, locale);
                        throw new NotificationTemplateManagerServerException(IdentityMgtConstants.ErrorMessages.
                                ERROR_CODE_INVALID_EMAIL_TEMPLATE_CONTENT.getCode(), errorMsg);
                    }
                }
                return templateContentElements;
            } else {
                String error = String.format(IdentityMgtConstants.ErrorMessages.
                        ERROR_CODE_NO_CONTENT_IN_TEMPLATE.getMessage(), displayName, locale);
                throw new NotificationTemplateManagerClientException(IdentityMgtConstants.ErrorMessages.
                        ERROR_CODE_NO_CONTENT_IN_TEMPLATE.getCode(), error);
            }
        } catch (RegistryException exception) {
            String error = IdentityMgtConstants.ErrorMessages.
                    ERROR_CODE_ERROR_RETRIEVING_TEMPLATE_OBJECT_FROM_REGISTRY.getMessage();
            throw new NotificationTemplateManagerServerException(IdentityMgtConstants.ErrorMessages.
                    ERROR_CODE_ERROR_RETRIEVING_TEMPLATE_OBJECT_FROM_REGISTRY.getCode(), error, exception);
        }
    }

    /**
     * Resolve notification channel to a server supported notification channel.
     *
     * @param notificationChannel Notification channel
     * @return Notification channel (EMAIL or SMS)
     */
    private String resolveNotificationChannel(String notificationChannel) {

        if (NotificationChannels.EMAIL_CHANNEL.getChannelType().equals(notificationChannel)) {
            return notificationChannel;
        } else if (NotificationChannels.SMS_CHANNEL.getChannelType().equals(notificationChannel)) {
            return notificationChannel;
        } else {
            if (log.isDebugEnabled()) {
                String message = String.format("Notification channel : %s is not supported by the server. "
                                + "Notification channel changed to : %s", notificationChannel,
                        IdentityGovernanceUtil.getDefaultNotificationChannel());
                log.debug(message);
            }
            return IdentityGovernanceUtil.getDefaultNotificationChannel();
        }
    }

    /**
     * Create a registry resource instance of the notification template.
     *
     * @param notificationTemplate Notification template
     * @return Resource
     * @throws NotificationTemplateManagerServerException If an error occurred while creating the resource
     */
    private Resource createTemplateRegistryResource(NotificationTemplate notificationTemplate)
            throws NotificationTemplateManagerServerException {

        String displayName = notificationTemplate.getDisplayName();
        String type = I18nEmailUtil.getNormalizedName(displayName);
        String locale = notificationTemplate.getLocale();
        String body = notificationTemplate.getBody();

        // Set template properties.
        Resource templateResource = new ResourceImpl();
        templateResource.setProperty(I18nMgtConstants.TEMPLATE_TYPE_DISPLAY_NAME, displayName);
        templateResource.setProperty(I18nMgtConstants.TEMPLATE_TYPE, type);
        templateResource.setProperty(I18nMgtConstants.TEMPLATE_LOCALE, locale);
        String[] templateContent;
        // Handle contents according to different channel types.
        if (NotificationChannels.EMAIL_CHANNEL.getChannelType().equals(notificationTemplate.getNotificationChannel())) {
            templateContent = new String[]{notificationTemplate.getSubject(), body, notificationTemplate.getFooter()};
            templateResource.setProperty(I18nMgtConstants.TEMPLATE_CONTENT_TYPE, notificationTemplate.getContentType());
        } else {
            templateContent = new String[]{body};
        }
        templateResource.setMediaType(RegistryConstants.TAG_MEDIA_TYPE);
        String content = new Gson().toJson(templateContent);
        try {
            byte[] contentByteArray = content.getBytes(StandardCharsets.UTF_8);
            templateResource.setContent(contentByteArray);
        } catch (RegistryException e) {
            String code =
                    I18nEmailUtil.prependOperationScenarioToErrorCode(
                            I18nMgtConstants.ErrorMessages.ERROR_CODE_ERROR_CREATING_REGISTRY_RESOURCE.getCode(),
                            I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
            String message =
                    String.format(
                            I18nMgtConstants.ErrorMessages.ERROR_CODE_ERROR_CREATING_REGISTRY_RESOURCE.getMessage(),
                            displayName, locale);
            throw new NotificationTemplateManagerServerException(code, message, e);
        }
        return templateResource;
    }

    @Override
    public void addNotificationTemplate(NotificationTemplate notificationTemplate, String tenantDomain)
            throws NotificationTemplateManagerException {

        validateNotificationTemplate(notificationTemplate);
        String notificationChannel = notificationTemplate.getNotificationChannel();

        Resource templateResource = createTemplateRegistryResource(notificationTemplate);
        String displayName = notificationTemplate.getDisplayName();
        String type = I18nEmailUtil.getNormalizedName(displayName);
        String locale = notificationTemplate.getLocale();

        String path = buildTemplateRootDirectoryPath(type, notificationChannel);
        try {
            // Check whether a template type root directory exists.
            if (!resourceMgtService.isResourceExists(path, tenantDomain)) {
                // Add new template type with relevant properties.
                addNotificationTemplateType(displayName, notificationChannel, tenantDomain);
                if (log.isDebugEnabled()) {
                    String msg = "Creating template type : %s in tenant registry : %s";
                    log.debug(String.format(msg, displayName, tenantDomain));
                }
            }
            resourceMgtService.putIdentityResource(templateResource, path, tenantDomain, locale);
        } catch (IdentityRuntimeException e) {
            String code = I18nEmailUtil.prependOperationScenarioToErrorCode(
                    I18nMgtConstants.ErrorMessages.ERROR_CODE_ERROR_ERROR_ADDING_TEMPLATE.getCode(),
                    I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
            String message =
                    String.format(I18nMgtConstants.ErrorMessages.ERROR_CODE_ERROR_ERROR_ADDING_TEMPLATE.getMessage(),
                            displayName, locale, tenantDomain);
            throw new NotificationTemplateManagerServerException(code, message);
        }
    }

    @Override
    public void addEmailTemplate(EmailTemplate emailTemplate, String tenantDomain) throws I18nEmailMgtException {

        NotificationTemplate notificationTemplate = buildNotificationTemplateFromEmailTemplate(emailTemplate);
        try {
            addNotificationTemplate(notificationTemplate, tenantDomain);
        } catch (NotificationTemplateManagerClientException e) {
            throw new I18nEmailMgtClientException(e.getMessage(), e);
        } catch (NotificationTemplateManagerInternalException e) {
            if (StringUtils.isNotBlank(e.getErrorCode())) {
                String errorCode = e.getErrorCode();
                if (errorCode.contains(I18nMgtConstants.ErrorMessages.ERROR_CODE_DUPLICATE_TEMPLATE_TYPE.getCode())) {
                    throw new I18nEmailMgtInternalException(
                            I18nMgtConstants.ErrorCodes.EMAIL_TEMPLATE_TYPE_ALREADY_EXISTS, e.getMessage(), e);
                }
            }
            throw new I18nEmailMgtInternalException(e.getMessage(), e);
        } catch (NotificationTemplateManagerException e) {
            throw new I18nEmailMgtServerException(e.getMessage(), e);
        }
    }


    @Override
    public void deleteEmailTemplate(String templateTypeName, String localeCode, String tenantDomain) throws
            I18nEmailMgtException {
        // validate the name and locale code.
        if (StringUtils.isBlank(templateTypeName)) {
            throw new I18nEmailMgtClientException("Cannot Delete template. Email displayName cannot be null.");
        }

        if (StringUtils.isBlank(localeCode)) {
            throw new I18nEmailMgtClientException("Cannot Delete template. Email locale cannot be null.");
        }

        String templateType = I18nEmailUtil.getNormalizedName(templateTypeName);
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + templateType;

        try {
            resourceMgtService.deleteIdentityResource(path, tenantDomain, localeCode);
        } catch (IdentityRuntimeException ex) {
            String msg = String.format("Error deleting %s:%s template from %s tenant registry.", templateTypeName,
                    localeCode, tenantDomain);
            handleServerException(msg, ex);
        }
    }

    @Override
    public void addDefaultEmailTemplates(String tenantDomain) throws I18nEmailMgtException {

        try {
            addDefaultNotificationTemplates(NotificationChannels.EMAIL_CHANNEL.getChannelType(), tenantDomain);
        } catch (NotificationTemplateManagerClientException e) {
            throw new I18nEmailMgtClientException(e.getMessage(), e);
        } catch (NotificationTemplateManagerInternalException e) {
            if (StringUtils.isNotBlank(e.getErrorCode())) {
                String errorCode = e.getErrorCode();
                if (errorCode.contains(I18nMgtConstants.ErrorMessages.ERROR_CODE_DUPLICATE_TEMPLATE_TYPE.getCode())) {
                    throw new I18nEmailMgtInternalException(
                            I18nMgtConstants.ErrorCodes.EMAIL_TEMPLATE_TYPE_ALREADY_EXISTS, e.getMessage(), e);
                }
            }
            throw new I18nEmailMgtInternalException(e.getMessage(), e);
        } catch (NotificationTemplateManagerException e) {
            throw new I18nEmailMgtServerException(e.getMessage(), e);
        }


        try {
            // load DTOs from the I18nEmailUtil class
            List<EmailTemplate> defaultTemplates = I18nEmailUtil.getDefaultEmailTemplates();
            // iterate through the list and write to registry!
            for (EmailTemplate emailTemplateDTO : defaultTemplates) {
                String templateTypeDisplayName = emailTemplateDTO.getTemplateDisplayName();
                String templateType = I18nEmailUtil.getNormalizedName(templateTypeDisplayName);

                String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + templateType; // template type root directory
                //Check for existence of each category, since some template may have migrated from earlier version
                //This will also add new template types provided from file, but won't update any existing
                if (!resourceMgtService.isResourceExists(path, tenantDomain)) {
                    try {
                        addEmailTemplate(emailTemplateDTO, tenantDomain);

                    } catch (DuplicateEmailTemplateException e) {
                        log.warn("Template" + templateTypeDisplayName + "already exists in the registry,Hence " +
                                "ignoring addition");
                    }
                    if (log.isDebugEnabled()) {
                        String msg = "Default template added to %s tenant registry : %n%s";
                        log.debug(String.format(msg, tenantDomain, emailTemplateDTO.toString()));
                    }
                }
            }

            if (log.isDebugEnabled()) {
                String msg = "Added %d default email templates to %s tenant registry";
                log.debug(String.format(msg, defaultTemplates.size(), tenantDomain));
            }
        } catch (IdentityRuntimeException ex) {
            String error = "Error when tried to check for default email templates in %s tenant registry";
            log.error(String.format(error, tenantDomain), ex);
        }
    }

    /**
     * Add the default notification templates which matches the given notification channel to the respective tenants
     * registry.
     *
     * @param notificationChannel Notification channel (Eg: SMS, EMAIL)
     * @param tenantDomain Tenant domain
     * @throws NotificationTemplateManagerException Error adding the default notification templates
     */
    @Override
    public void addDefaultNotificationTemplates(String notificationChannel, String tenantDomain)
            throws NotificationTemplateManagerException {

        // Get the list of Default notification templates.
        List<NotificationTemplate> notificationTemplates =
                getDefaultNotificationTemplates(notificationChannel);
        int numberOfAddedTemplates = 0;
        try {
            for (NotificationTemplate template : notificationTemplates) {
                String displayName = template.getDisplayName();
                String type = I18nEmailUtil.getNormalizedName(displayName);
                String path = buildTemplateRootDirectoryPath(type, notificationChannel);
                String templateLocale = template.getLocale();
            /*Check for existence of each category, since some template may have migrated from earlier version
            This will also add new template types provided from file, but won't update any existing template*/
                if (!resourceMgtService.isResourceExists(addLocaleToTemplateTypeResourcePath(path, templateLocale),
                                                                                             tenantDomain)) {
                    try {
                        addNotificationTemplate(template, tenantDomain);
                        if (log.isDebugEnabled()) {
                            String msg = "Default template added to %s tenant registry : %n%s";
                            log.debug(String.format(msg, tenantDomain, template.toString()));
                        }
                        numberOfAddedTemplates++;
                    } catch (NotificationTemplateManagerInternalException e) {
                        log.warn("Template : " + displayName + "already exists in the registry. Hence " +
                                "ignoring addition");
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug(String.format("Added %d default %s templates to the tenant registry : %s",
                        numberOfAddedTemplates, notificationChannel, tenantDomain));
            }
        } catch (IdentityRuntimeException ex) {
            String error = "Error when tried to check for default email templates in tenant registry : %s";
            log.error(String.format(error, tenantDomain), ex);
        }
    }

    /**
     * Add the locale to the template type resource path.
     *
     * @param path  Email template path
     * @param locale Locale code of the email template
     * @return Email template resource path
     */
    private String addLocaleToTemplateTypeResourcePath(String path, String locale) {

        if (StringUtils.isNotBlank(locale)) {
            return path + PATH_SEPARATOR + locale.toLowerCase();
        } else {
            return path;
        }
    }

    /**
     * Get the notification templates which matches the given notification template type.
     *
     * @param notificationChannel Notification channel type. (Eg: EMAIL, SMS)
     * @return List of default notification templates
     */
    @Override
    public List<NotificationTemplate> getDefaultNotificationTemplates(String notificationChannel) {

        String configFilePath = buildNotificationTemplateConfigPath(notificationChannel);
        File configFile = new File(configFilePath);
        if (!configFile.exists()) {
            log.error("Email Configuration File is not present at: " + configFilePath);
        }

        List<NotificationTemplate> defaultNotificationTemplates = new ArrayList<>();
        XMLStreamReader xmlStreamReader = null;
        InputStream inputStream = null;

        try {
            inputStream = new FileInputStream(configFile);
            xmlStreamReader = XMLInputFactory.newInstance().createXMLStreamReader(inputStream);
            StAXOMBuilder builder = new StAXOMBuilder(xmlStreamReader);

            OMElement documentElement = builder.getDocumentElement();
            Iterator iterator = documentElement.getChildElements();
            while (iterator.hasNext()) {
                OMElement omElement = (OMElement) iterator.next();
                Map<String, String> templateContentMap = getNotificationTemplateContent(omElement);

                // Create notification template model with the template attributes.
                NotificationTemplate notificationTemplate = new NotificationTemplate();
                notificationTemplate.setType(omElement.getAttributeValue(new QName(I18nMgtConstants.TEMPLATE_TYPE)));
                notificationTemplate.setDisplayName(
                        omElement.getAttributeValue(new QName(I18nMgtConstants.TEMPLATE_TYPE_DISPLAY_NAME)));
                notificationTemplate
                        .setLocale(omElement.getAttributeValue(new QName(I18nMgtConstants.TEMPLATE_LOCALE)));
                notificationTemplate.setBody(templateContentMap.get(I18nMgtConstants.TEMPLATE_BODY));
                notificationTemplate.setNotificationChannel(notificationChannel);

                if (NotificationChannels.EMAIL_CHANNEL.getChannelType().equals(notificationChannel)) {
                    notificationTemplate.setContentType(
                            omElement.getAttributeValue(new QName(I18nMgtConstants.TEMPLATE_CONTENT_TYPE)));
                    notificationTemplate.setFooter(templateContentMap.get(I18nMgtConstants.TEMPLATE_FOOTER));
                    notificationTemplate.setSubject(templateContentMap.get(I18nMgtConstants.TEMPLATE_SUBJECT));
                }
                defaultNotificationTemplates.add(notificationTemplate);
            }
        } catch (XMLStreamException | FileNotFoundException e) {
            log.warn("Error while loading default templates to the registry.", e);
        } finally {
            try {
                if (xmlStreamReader != null) {
                    xmlStreamReader.close();
                }
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (XMLStreamException e) {
                log.error("Error while closing XML stream", e);
            } catch (IOException e) {
                log.error("Error while closing input stream", e);
            }
        }
        return defaultNotificationTemplates;
    }

    /**
     * Get the template attributes of the notification template such as SUBJECT, BODY, EMAIL.
     *
     * @param templateElement OMElement
     * @return List of attributes in the notification template
     */
    private static Map<String, String> getNotificationTemplateContent(OMElement templateElement) {

        Map<String, String> notificationTemplateContent = new HashMap<>();
        Iterator it = templateElement.getChildElements();
        while (it.hasNext()) {
            OMElement element = (OMElement) it.next();
            String elementName = element.getLocalName();
            String elementText = element.getText();
            if (StringUtils.equalsIgnoreCase(I18nMgtConstants.TEMPLATE_SUBJECT, elementName)) {
                notificationTemplateContent.put(I18nMgtConstants.TEMPLATE_SUBJECT, elementText);
            } else if (StringUtils.equalsIgnoreCase(I18nMgtConstants.TEMPLATE_BODY, elementName)) {
                notificationTemplateContent.put(I18nMgtConstants.TEMPLATE_BODY, elementText);
            } else if (StringUtils.equalsIgnoreCase(I18nMgtConstants.TEMPLATE_FOOTER, elementName)) {
                notificationTemplateContent.put(I18nMgtConstants.TEMPLATE_FOOTER, elementText);
            }
        }
        return notificationTemplateContent;
    }

    /**
     * Build the file path of the notification template config file according to the given channel.
     *
     * @param notificationChannel Notification channel name (EMAIL,SMS)
     * @return Path of the configuration file
     */
    private String buildNotificationTemplateConfigPath(String notificationChannel) {

        if (NotificationChannels.SMS_CHANNEL.getChannelType().equals(notificationChannel)) {
            return CarbonUtils.getCarbonConfigDirPath() + File.separator +
                    I18nMgtConstants.SMS_CONF_DIRECTORY + File.separator + I18nMgtConstants.SMS_TEMPLAE_ADMIN_CONF_FILE;
        }
        return CarbonUtils.getCarbonConfigDirPath() + File.separator +
                I18nMgtConstants.EMAIL_CONF_DIRECTORY + File.separator + I18nMgtConstants.EMAIL_ADMIN_CONF_FILE;
    }

    @Override
    public boolean isEmailTemplateExists(String templateTypeDisplayName, String locale, String tenantDomain)
            throws I18nEmailMgtException {

        // get template directory name from display name.
        String normalizedTemplateName = I18nEmailUtil.getNormalizedName(templateTypeDisplayName);
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + normalizedTemplateName +
                        PATH_SEPARATOR + locale.toLowerCase();

        try {
            Resource template = resourceMgtService.getIdentityResource(path, tenantDomain);
            return template != null;
        } catch (IdentityRuntimeException e) {
            String error = String.format("Error when retrieving email templates of %s tenant.", tenantDomain);
            throw new I18nEmailMgtServerException(error, e);
        }
    }

    @Override
    public boolean isEmailTemplateTypeExists(String templateTypeDisplayName, String tenantDomain)
            throws I18nEmailMgtException {

        // get template directory name from display name.
        String normalizedTemplateName = I18nEmailUtil.getNormalizedName(templateTypeDisplayName);
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + normalizedTemplateName;

        try {
            Resource templateType = resourceMgtService.getIdentityResource(path, tenantDomain);
            return templateType != null;
        } catch (IdentityRuntimeException e) {
            String error = String.format("Error when retrieving email templates of %s tenant.", tenantDomain);
            throw new I18nEmailMgtServerException(error, e);
        }
    }

    /**
     * Validate the attributes of a notification template.
     *
     * @param notificationTemplate Notification template
     * @throws NotificationTemplateManagerClientException Invalid notification template.
     */
    private void validateNotificationTemplate(NotificationTemplate notificationTemplate)
            throws NotificationTemplateManagerClientException {

        if (notificationTemplate == null) {
            String errorCode =
                    I18nEmailUtil.prependOperationScenarioToErrorCode(
                            I18nMgtConstants.ErrorMessages.ERROR_CODE_NULL_TEMPLATE_OBJECT.getCode(),
                            I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
            throw new NotificationTemplateManagerClientException(errorCode,
                    I18nMgtConstants.ErrorMessages.ERROR_CODE_NULL_TEMPLATE_OBJECT.getMessage());
        }
        String displayName = notificationTemplate.getDisplayName();
        validateDisplayNameOfTemplateType(displayName);
        String normalizedDisplayName = I18nEmailUtil.getNormalizedName(displayName);
        if (!StringUtils.equalsIgnoreCase(normalizedDisplayName, notificationTemplate.getType())) {
            if (log.isDebugEnabled()) {
                String message = String.format("In the template normalizedDisplayName : %s is not equal to the " +
                                "template type : %s. Therefore template type is sent to : %s", normalizedDisplayName,
                        notificationTemplate.getType(), normalizedDisplayName);
                log.debug(message);
            }
            notificationTemplate.setType(normalizedDisplayName);
        }
        validateTemplateLocale(notificationTemplate.getLocale());
        String body = notificationTemplate.getBody();
        String subject = notificationTemplate.getSubject();
        String footer = notificationTemplate.getFooter();
        if (StringUtils.isBlank(notificationTemplate.getNotificationChannel())) {
            String errorCode =
                    I18nEmailUtil.prependOperationScenarioToErrorCode(
                            I18nMgtConstants.ErrorMessages.ERROR_CODE_EMPTY_TEMPLATE_CHANNEL.getCode(),
                            I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
            throw new NotificationTemplateManagerClientException(errorCode,
                    I18nMgtConstants.ErrorMessages.ERROR_CODE_EMPTY_TEMPLATE_CHANNEL.getMessage());
        }
        if (NotificationChannels.SMS_CHANNEL.getChannelType().equals(notificationTemplate.getNotificationChannel())) {
            if (StringUtils.isBlank(body)) {
                String errorCode =
                        I18nEmailUtil.prependOperationScenarioToErrorCode(
                                I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_SMS_TEMPLATE.getCode(),
                                I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
                throw new NotificationTemplateManagerClientException(errorCode,
                        I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_SMS_TEMPLATE.getMessage());
            }
            if (StringUtils.isNotBlank(subject) || StringUtils.isNotBlank(footer)) {
                String errorCode =
                        I18nEmailUtil.prependOperationScenarioToErrorCode(
                                I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_SMS_TEMPLATE_CONTENT.getCode(),
                                I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
                throw new NotificationTemplateManagerClientException(errorCode,
                        I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_SMS_TEMPLATE_CONTENT.getMessage());
            }
        } else {
            if (StringUtils.isBlank(subject) || StringUtils.isBlank(body) || StringUtils.isBlank(footer)) {
                String errorCode =
                        I18nEmailUtil.prependOperationScenarioToErrorCode(
                                I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_EMAIL_TEMPLATE.getCode(),
                                I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
                throw new NotificationTemplateManagerClientException(errorCode,
                        I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_EMAIL_TEMPLATE.getMessage());
            }
        }
    }

    /**
     * Validate the displayName of a template type.
     *
     * @param templateDisplayName Display name of the notification template
     * @throws I18nEmailMgtClientException Invalid notification template
     */
    private void validateTemplateType(String templateDisplayName, String tenantDomain)
            throws I18nEmailMgtClientException {

        try {
            validateDisplayNameOfTemplateType(templateDisplayName);
        } catch (NotificationTemplateManagerClientException e) {
            if (StringUtils.isNotBlank(e.getErrorCode())) {
                String errorCode = e.getErrorCode();
                if (errorCode.contains(I18nMgtConstants.ErrorMessages.ERROR_CODE_EMPTY_TEMPLATE_NAME.getCode())) {
                    throw new I18nEmailMgtClientException("Template Type display name cannot be null", e);
                }
                if (errorCode.contains(
                        I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_CHARACTERS_IN_TEMPLATE_NAME.getCode())) {
                    throw new I18nEmailMgtClientException(e.getMessage(), e);
                }
            }
            throw new I18nEmailMgtClientException("Invalid notification template", e);
        }
    }

    /**
     * Loop through all template resources of a given template type registry path and return a list of EmailTemplate
     * objects.
     *
     * @param templateTypeRegistryPath Registry path of the template type.
     * @param tenantDomain             Tenant domain.
     * @return List of extracted EmailTemplate objects.
     * @throws RegistryException if any error occurred.
     */
    private List<EmailTemplate> getAllTemplatesOfTemplateTypeFromRegistry(String templateTypeRegistryPath,
                                                                          String tenantDomain)
            throws RegistryException, I18nEmailMgtClientException {

        List<EmailTemplate> templateList = new ArrayList<>();
        Collection templateType = (Collection) resourceMgtService.getIdentityResource(templateTypeRegistryPath,
                tenantDomain);

        if (templateType == null) {
            String type = templateTypeRegistryPath.split(PATH_SEPARATOR)[
                    templateTypeRegistryPath.split(PATH_SEPARATOR).length - 1];
            String message =
                    String.format("Email Template Type: %s not found in %s tenant registry.", type, tenantDomain);
            throw new I18nEmailMgtClientException(EMAIL_TEMPLATE_TYPE_NOT_FOUND, message);
        }
        for (String template : templateType.getChildren()) {
            Resource templateResource = resourceMgtService.getIdentityResource(template, tenantDomain);
            if (templateResource != null) {
                try {
                    EmailTemplate templateDTO = I18nEmailUtil.getEmailTemplate(templateResource);
                    templateList.add(templateDTO);
                } catch (I18nEmailMgtException ex) {
                    log.error("Failed retrieving a template object from the registry resource", ex);
                }
            }
        }
        return templateList;
    }

    private void handleServerException(String errorMsg, Throwable ex) throws I18nEmailMgtServerException {

        log.error(errorMsg);
        throw new I18nEmailMgtServerException(errorMsg, ex);
    }

    /**
     * Validate the display name of the notification template.
     *
     * @param displayName Display name
     * @throws NotificationTemplateManagerClientException Invalid notification template name
     */
    private void validateDisplayNameOfTemplateType(String displayName)
            throws NotificationTemplateManagerClientException {

        if (StringUtils.isBlank(displayName)) {
            String errorCode =
                    I18nEmailUtil.prependOperationScenarioToErrorCode(
                            I18nMgtConstants.ErrorMessages.ERROR_CODE_EMPTY_TEMPLATE_NAME.getCode(),
                            I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
            throw new NotificationTemplateManagerClientException(errorCode,
                    I18nMgtConstants.ErrorMessages.ERROR_CODE_EMPTY_TEMPLATE_NAME.getMessage());
        }
        /*Template name can contain only alphanumeric characters and spaces, it can't contain registry invalid
        characters*/
        String[] whiteListPatterns = {TEMPLATE_REGEX_KEY};
        String[] blackListPatterns = {REGISTRY_INVALID_CHARS};
        if (!IdentityValidationUtil.isValid(displayName, whiteListPatterns, blackListPatterns)) {
            String errorCode =
                    I18nEmailUtil.prependOperationScenarioToErrorCode(
                            I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_CHARACTERS_IN_TEMPLATE_NAME.getCode(),
                            I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
            String message =
                    String.format(
                            I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_CHARACTERS_IN_TEMPLATE_NAME.getMessage(),
                            displayName);
            throw new NotificationTemplateManagerClientException(errorCode, message);
        }
    }

    /**
     * Validates the locale code of a notification template.
     *
     * @param locale Locale code
     * @throws NotificationTemplateManagerClientException Invalid notification template
     */
    private void validateTemplateLocale(String locale) throws NotificationTemplateManagerClientException {

        if (StringUtils.isBlank(locale)) {
            String errorCode =
                    I18nEmailUtil.prependOperationScenarioToErrorCode(
                            I18nMgtConstants.ErrorMessages.ERROR_CODE_EMPTY_LOCALE.getCode(),
                            I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
            throw new NotificationTemplateManagerClientException(errorCode,
                    I18nMgtConstants.ErrorMessages.ERROR_CODE_EMPTY_LOCALE.getMessage());
        }
        // Regex check for registry invalid chars.
        if (!IdentityValidationUtil.isValidOverBlackListPatterns(locale, REGISTRY_INVALID_CHARS)) {
            String errorCode =
                    I18nEmailUtil.prependOperationScenarioToErrorCode(
                            I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_CHARACTERS_IN_LOCALE.getCode(),
                            I18nMgtConstants.ErrorScenarios.EMAIL_TEMPLATE_MANAGER);
            String message =
                    String.format(I18nMgtConstants.ErrorMessages.ERROR_CODE_INVALID_CHARACTERS_IN_LOCALE.getMessage(),
                            locale);
            throw new NotificationTemplateManagerClientException(errorCode, message);
        }
    }

    /**
     * Build the template type root directory path.
     *
     * @param type                Template type
     * @param notificationChannel Notification channel (SMS or EMAIL)
     * @return Root directory path
     */
    private String buildTemplateRootDirectoryPath(String type, String notificationChannel) {

        if (NotificationChannels.SMS_CHANNEL.getChannelType().equals(notificationChannel)) {
            return SMS_TEMPLATE_PATH + PATH_SEPARATOR + type;
        }
        return EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + type;
    }

    /**
     * Build notification template model from the email template attributes.
     *
     * @param emailTemplate EmailTemplate
     * @return NotificationTemplate
     */
    private NotificationTemplate buildNotificationTemplateFromEmailTemplate(EmailTemplate emailTemplate) {

        NotificationTemplate notificationTemplate = new NotificationTemplate();
        notificationTemplate.setNotificationChannel(NotificationChannels.EMAIL_CHANNEL.getChannelType());
        notificationTemplate.setSubject(emailTemplate.getSubject());
        notificationTemplate.setBody(emailTemplate.getBody());
        notificationTemplate.setFooter(emailTemplate.getFooter());
        notificationTemplate.setType(emailTemplate.getTemplateType());
        notificationTemplate.setDisplayName(emailTemplate.getTemplateDisplayName());
        notificationTemplate.setLocale(emailTemplate.getLocale());
        notificationTemplate.setContentType(emailTemplate.getEmailContentType());
        return notificationTemplate;
    }
}

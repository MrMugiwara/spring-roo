package org.springframework.roo.project.packaging;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.felix.scr.annotations.Component;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.springframework.roo.model.JavaPackage;
import org.springframework.roo.process.manager.FileManager;
import org.springframework.roo.project.GAV;
import org.springframework.roo.project.Path;
import org.springframework.roo.project.PathResolver;
import org.springframework.roo.project.ProjectOperations;
import org.springframework.roo.support.logging.HandlerUtils;
import org.springframework.roo.support.util.DomUtils;
import org.springframework.roo.support.util.FileUtils;
import org.springframework.roo.support.util.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Convenient superclass for core or third-party addons to implement a
 * {@link PackagingProvider}. Uses the "Template Method" GoF pattern.
 *
 * @author Andrew Swan
 * @author Paula Navarro
 * @author Juan Carlos García
 * @since 1.2.0
 */
@Component(componentAbstract = true)
public abstract class AbstractPackagingProvider implements PackagingProvider {

  // ------------ OSGi component attributes ----------------
  private BundleContext context;

  protected void activate(final ComponentContext cContext) {
    context = cContext.getBundleContext();
  }

  private static final String DEFAULT_VERSION = "0.1.0.BUILD-SNAPSHOT";
  private static final String JAVA_PRODUCT_VERSION_PLACEHOLDER = "JAVA_PRODUCT_VERSION";
  private static final String ASPECTJ_PLUGIN_VERSION_PLACEHOLDER = "ASPECTJ_PLUGIN_VERSION";
  private static final String ASCIIDOCLET_PLUGIN_VERSION_PLACEHOLDER = "ASCIIDOCLET_PLUGIN_VERSION";
  protected static final Logger LOGGER = HandlerUtils.getLogger(PackagingProvider.class);
  /**
   * The name of the POM property that stores the packaging provider's ID.
   */
  public static final String ROO_PACKAGING_PROVIDER_PROPERTY = "roo.packaging.provider";

  private static final String VERSION_ELEMENT = "version";

  protected FileManager fileManager;
  protected PathResolver pathResolver;
  private final String id;
  private final String name;
  private final String pomTemplate;
  private final String pomModuleTemplate;

  /**
   * Constructor
   *
   * @param id the unique ID of this packaging type, see
   *            {@link PackagingProvider#getId()}
   * @param name the name of this type of packaging as used in the POM
   *            (required)
   * @param pomTemplate the path of this packaging type's POM template,
   *            relative to its own package, as per
   *            {@link Class#getResourceAsStream(String)}; this template
   *            should contain a "parent" element with its own groupId,
   *            artifactId, and version elements; this parent element will be
   *            removed if not required
   */
  protected AbstractPackagingProvider(final String id, final String name, final String pomTemplate) {
    this(id, name, pomTemplate, pomTemplate);
  }

  /**
   * Constructor
   *
   * @param id the unique ID of this packaging type, see
   *            {@link PackagingProvider#getId()}
   * @param name the name of this type of packaging as used in the POM
   *            (required)
   * @param pomTemplate the path of this packaging type's POM template,
   *            relative to its own package, as per
   *            {@link Class#getResourceAsStream(String)}; this template
   *            should contain a "parent" element with its own groupId,
   *            artifactId, and version elements; this parent element will be
   *            removed if not required
   * @param pomModuleTemplate the path of this packaging type's POM module template,
   *            relative to its own package, as per
   *            {@link Class#getResourceAsStream(String)}; this template
   *            should contain a "parent" element with its own groupId,
   *            artifactId, and version elements; can be <code>null</code>
   */
  protected AbstractPackagingProvider(final String id, final String name, final String pomTemplate,
      final String pomModuleTemplate) {
    Validate.notBlank(id, "ID is required");
    Validate.notBlank(name, "Name is required");
    Validate.notBlank(pomTemplate, "POM template path is required");
    this.id = id;
    this.name = name;
    this.pomTemplate = pomTemplate;
    this.pomModuleTemplate = pomModuleTemplate;
  }

  public String createArtifacts(final JavaPackage topLevelPackage,
      final String nullableProjectName, final String javaVersion, final GAV parentPom,
      final String module, final ProjectOperations projectOperations) {
    final String pomPath =
        createPom(topLevelPackage, nullableProjectName, javaVersion, parentPom, module,
            projectOperations);
    // ROO-3687: Log4J is not necessary to install on this new version
    // of Spring Roo.
    // createOtherArtifacts(topLevelPackage, module, projectOperations);
    return pomPath;
  }

  /**
   * Subclasses can override this method to create any other required files or
   * directories (apart from the POM, which has previously been generated by
   * {@link #createPom}).
   * <p>
   * This implementation sets up the Log4j configuration file for the root
   * module.
   *
   * @param topLevelPackage
   * @param module the unqualified name of the module being created (empty
   *            means the root or only module)
   * @param projectOperations can't be injected as it would create a circular
   *            dependency
   */
  protected void createOtherArtifacts(final JavaPackage topLevelPackage, final String module,
      final ProjectOperations projectOperations) {
    if (StringUtils.isBlank(module)) {
      setUpLog4jConfiguration();
    }
  }

  /**
   * Creates the Maven POM using the subclass' POM template as follows:
   * <ul>
   * <li>sets the parent POM to the given parent (if any)</li>
   * <li>sets the groupId to the result of {@link #getGroupId}, omitting this
   * element if it's the same as the parent's groupId (as per Maven best
   * practice)</li>
   * <li>sets the artifactId to the result of {@link #getArtifactId}</li>
   * <li>sets the packaging to the result of {@link #getName()}</li>
   * <li>sets the project name to the result of {@link #getProjectName}</li>
   * <li>replaces all occurrences of {@link #JAVA_PRODUCT_VERSION_PLACEHOLDER} with
   * the given Java version</li>
   * </ul>
   * This method makes as few assumptions about the POM template as possible,
   * to make life easier for anyone writing a {@link PackagingProvider}.
   *
   * @param topLevelPackage the new project or module's top-level Java package
   *            (required)
   * @param projectName the project name provided by the user (can be blank)
   * @param javaVersion the Java version to substitute into the POM (required)
   * @param parentPom the Maven coordinates of the parent POM (can be
   *            <code>null</code>)
   * @param module the unqualified name of the Maven module to which the new
   *            POM belongs
   * @param projectOperations cannot be injected otherwise it's a circular
   *            dependency
   * @return the path of the newly created POM
   */
  protected String createPom(final JavaPackage topLevelPackage, final String projectName,
      final String javaVersion, final GAV parentPom, final String module,
      final ProjectOperations projectOperations) {

    final Document pom;
    final String groupId;
    final boolean isModule =
        StringUtils.isNotBlank(module) && StringUtils.isNotBlank(pomModuleTemplate);

    Validate.isTrue(isModule || StringUtils.isNotBlank(javaVersion), "Java version required");
    Validate.notNull(topLevelPackage, "Top level package required");

    // Read the POM template from the classpath
    if (!isModule) {
      pom = XmlUtils.readXml(FileUtils.getInputStream(getClass(), pomTemplate));
      groupId = getGroupId(topLevelPackage);
    } else {
      pom = XmlUtils.readXml(FileUtils.getInputStream(getClass(), pomModuleTemplate));
      groupId = parentPom.getGroupId();
    }
    final Element root = pom.getDocumentElement();

    // name
    final String mavenName = getProjectName(projectName, module, topLevelPackage);
    if (StringUtils.isNotBlank(mavenName)) {
      // If the user wants this element in the traditional place, ensure
      // the template already contains it
      DomUtils.createChildIfNotExists("name", root, pom).setTextContent(mavenName.trim());
    } else {
      DomUtils.removeElements("name", root);
    }

    // groupId and parent
    setGroupIdAndParent(groupId, parentPom, root, pom);

    // artifactId
    final String artifactId = getArtifactId(projectName, module, topLevelPackage);
    Validate.notBlank(artifactId, "Maven artifactIds cannot be blank");
    DomUtils.createChildIfNotExists("artifactId", root, pom).setTextContent(artifactId.trim());

    if (!isModule) {

      // version
      final Element existingVersionElement =
          DomUtils.getChildElementByTagName(root, VERSION_ELEMENT);
      if (existingVersionElement == null) {
        DomUtils.createChildElement(VERSION_ELEMENT, root, pom).setTextContent(DEFAULT_VERSION);
      }

      // Java product version (8, 7 ,6)
      final List<Element> javaProductVersionElements =
          XmlUtils.findElements("//*[.='" + JAVA_PRODUCT_VERSION_PLACEHOLDER + "']", root);
      for (final Element versionElement : javaProductVersionElements) {
        versionElement.setTextContent(javaVersion);
      }

      // AspectJ Plugin Versions
      final List<Element> aspectJPluginVersionElements =
          XmlUtils.findElements("//*[.='" + ASPECTJ_PLUGIN_VERSION_PLACEHOLDER + "']", root);
      for (final Element aspectJPluginVersion : aspectJPluginVersionElements) {
        aspectJPluginVersion.setTextContent("1.8");
        //        if ("1.8".equals(javaVersion)) {
        //          aspectJPluginVersion.setTextContent("1.8");
        //        } else if ("1.7".equals(javaVersion)) {
        //          aspectJPluginVersion.setTextContent("1.8");
        //        } else if ("1.6".equals(javaVersion)) {
        //          aspectJPluginVersion.setTextContent("1.8");
        //        }
      }

      // Asciidoclet Plugin versions
      final List<Element> asciidocletPluginVersionElements =
          XmlUtils.findElements("//*[.='" + ASCIIDOCLET_PLUGIN_VERSION_PLACEHOLDER + "']", root);
      for (final Element versionElement : asciidocletPluginVersionElements) {
        versionElement.setTextContent("1.5.4");
      }

    }

    // packaging
    DomUtils.createChildIfNotExists("packaging", root, pom).setTextContent(name);
    setPackagingProviderId(pom);


    // Write the new POM to disk
    final String pomPath =
        getPathResolver().getIdentifier(Path.ROOT.getModulePathId(module), "pom.xml");
    getFileManager().createOrUpdateTextFileIfRequired(pomPath, XmlUtils.nodeToString(pom), true);
    return pomPath;
  }

  /**
   * Returns the text to be inserted into the POM's
   * <code>&lt;artifactId&gt;</code> element. This implementation simply
   * delegates to {@link #getProjectName}. Subclasses can override this method
   * to use a different strategy.
   *
   * @param nullableProjectName the project name entered by the user (can be
   *            blank)
   * @param module the name of the module being created (blank for the root
   *            module)
   * @param topLevelPackage the project or module's top level Java package
   *            (required)
   * @return a non-blank artifactId
   */
  protected String getArtifactId(final String nullableProjectName, final String module,
      final JavaPackage topLevelPackage) {
    if (nullableProjectName == null) {
      String packageName = StringUtils.replace(module, "-", ".");
      return StringUtils.defaultIfEmpty(packageName, topLevelPackage.getLastElement());
    } else {
      return nullableProjectName.toLowerCase().replaceAll("\\s+", "");
    }
  }

  /**
   * Returns the fully-qualified name of the given module, relative to the
   * currently focused module.
   *
   * @param moduleName can be blank for the root or only module
   * @param projectOperations
   * @return
   */
  protected final String getFullyQualifiedModuleName(final String moduleName,
      final ProjectOperations projectOperations) {
    if (StringUtils.isBlank(moduleName)) {
      return "";
    }
    final String focusedModuleName = projectOperations.getFocusedModuleName();
    if (StringUtils.isBlank(focusedModuleName)) {
      return moduleName;
    }
    return focusedModuleName + File.separator + moduleName;
  }

  /**
   * Returns the groupId of the project or module being created. This
   * implementation simply uses the fully-qualified name of the given Java
   * package. Subclasses can override this method to use a different strategy.
   *
   * @param topLevelPackage the new project or module's top-level Java package
   *            (required)
   * @return
   */
  protected String getGroupId(final JavaPackage topLevelPackage) {
    return topLevelPackage.getFullyQualifiedPackageName();
  }

  public final String getId() {
    return id;
  }

  /**
   * Returns the package-relative path to this {@link PackagingProvider}'s POM
   * template.
   *
   * @return a non-blank path
   */
  String getPomTemplate() {
    return pomTemplate;
  }

  /**
   * Returns the package-relative path to this {@link PackagingProvider}'s POM
   * module template.
   *
   * @return a non-blank path
   */
  String getPomModuleTemplate() {
    return pomModuleTemplate;
  }

  /**
   * Returns the text to be inserted into the POM's <code>&lt;name&gt;</code>
   * element. This implementation uses the given project name if not blank,
   * otherwise the last element of the given Java package. Subclasses can
   * override this method to use a different strategy.
   *
   * @param nullableProjectName the project name entered by the user (can be
   *            blank)
   * @param module the name of the module being created (blank for the root
   *            module)
   * @param topLevelPackage the project or module's top level Java package
   *            (required)
   * @return a blank name if none is required
   */
  protected String getProjectName(final String nullableProjectName, final String module,
      final JavaPackage topLevelPackage) {
    String packageName =
        StringUtils.defaultIfEmpty(nullableProjectName, StringUtils.replace(module, "-", "."));
    return StringUtils.defaultIfEmpty(packageName, topLevelPackage.getLastElement());
  }

  /**
   * Sets the Maven groupIds of the parent and/or project as necessary
   *
   * @param projectGroupId the project's groupId (required)
   * @param parentPom the Maven coordinates of the parent POM (can be
   *            <code>null</code>)
   * @param root the root element of the POM document (required)
   * @param pom the POM document (required)
   */
  protected void setGroupIdAndParent(final String projectGroupId, final GAV parentPom,
      final Element root, final Document pom) {
    final Element parentPomElement = DomUtils.createChildIfNotExists("parent", root, pom);
    final Element projectGroupIdElement = DomUtils.createChildIfNotExists("groupId", root, pom);

    // ROO-3687: By default, Spring IO Platform will be the parent pom.
    // If developer specify new parent pom, update parent with the new one.
    if (parentPom != null) {
      // Parent's groupId, artifactId, and version
      DomUtils.createChildIfNotExists("groupId", parentPomElement, pom).setTextContent(
          parentPom.getGroupId());
      DomUtils.createChildIfNotExists("artifactId", parentPomElement, pom).setTextContent(
          parentPom.getArtifactId());
      DomUtils.createChildIfNotExists(VERSION_ELEMENT, parentPomElement, pom).setTextContent(
          parentPom.getVersion());

      // Project groupId (if necessary)
      if (projectGroupId.equals(parentPom.getGroupId())) {
        // Maven best practice is to inherit the groupId from the parent
        root.removeChild(projectGroupIdElement);
        DomUtils.removeTextNodes(root);
      } else {
        // Project has its own groupId => needs to be explicit
        projectGroupIdElement.setTextContent(projectGroupId);
      }
    } else {
      // Project has its own groupId => needs to be explicit
      projectGroupIdElement.setTextContent(projectGroupId);
    }
  }

  /**
   * Stores the ID of this {@link PackagingProvider} as a POM property called
   * {@value #ROO_PACKAGING_PROVIDER_PROPERTY}. Subclasses can override this
   * method, but be aware that Roo needs some way of working out from a given
   * <code>pom.xml</code> file which {@link PackagingProvider} should be used.
   *
   * @param pom the DOM document for the POM being created
   */
  protected void setPackagingProviderId(final Document pom) {
    final Node propertiesElement =
        DomUtils.createChildIfNotExists("properties", pom.getDocumentElement(), pom);
    DomUtils.createChildIfNotExists(ROO_PACKAGING_PROVIDER_PROPERTY, propertiesElement, pom)
        .setTextContent(getId());
  }

  private void setUpLog4jConfiguration() {
    final String log4jConfigFile =
        getPathResolver().getFocusedIdentifier(Path.SRC_MAIN_RESOURCES, "log4j.properties");
    final InputStream templateInputStream =
        FileUtils.getInputStream(getClass(), "log4j.properties-template");
    OutputStream outputStream = null;
    try {
      outputStream = getFileManager().createFile(log4jConfigFile).getOutputStream();
      IOUtils.copy(templateInputStream, outputStream);
    } catch (final IOException e) {
      LOGGER.warning("Unable to install log4j logging configuration");
    } finally {
      IOUtils.closeQuietly(templateInputStream);
      IOUtils.closeQuietly(outputStream);
    }
  }

  public FileManager getFileManager() {
    if (fileManager == null) {
      // Get all Services implement FileManager interface
      try {
        ServiceReference<?>[] references =
            context.getAllServiceReferences(FileManager.class.getName(), null);

        for (ServiceReference<?> ref : references) {
          return (FileManager) context.getService(ref);
        }

        return null;

      } catch (InvalidSyntaxException e) {
        LOGGER.warning("Cannot load FileManager on AbstractPackagingProvider.");
        return null;
      }
    } else {
      return fileManager;
    }
  }

  public PathResolver getPathResolver() {
    if (pathResolver == null) {
      // Get all Services implement PathResolver interface
      try {
        ServiceReference<?>[] references =
            context.getAllServiceReferences(PathResolver.class.getName(), null);
        for (ServiceReference<?> ref : references) {
          pathResolver = (PathResolver) context.getService(ref);
          return pathResolver;
        }
        return null;

      } catch (InvalidSyntaxException e) {
        LOGGER.warning("Cannot load PathResolver on AbstractPackagingProvider.");
        return null;
      }
    } else {
      return pathResolver;
    }
  }
}

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spark.launcher;

import com.facebook.presto.spark.classloader_interface.IPrestoSparkQueryExecution;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkQueryExecutionFactory;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkService;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkServiceFactory;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkTaskExecutorFactory;
import com.facebook.presto.spark.classloader_interface.PrestoSparkConfiguration;
import com.facebook.presto.spark.classloader_interface.PrestoSparkSession;
import com.facebook.presto.spark.classloader_interface.PrestoSparkTaskExecutorFactoryProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.airline.Command;
import io.airlift.airline.HelpOption;

import javax.inject.Inject;

import java.io.File;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

import static com.facebook.presto.spark.launcher.LauncherUtils.checkDirectory;
import static com.facebook.presto.spark.launcher.LauncherUtils.checkFile;
import static com.facebook.presto.spark.launcher.LauncherUtils.readFileUtf8;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Arrays.sort;
import static java.util.Objects.requireNonNull;

@Command(name = "presto-spark-launcher", description = "Presto on Spark launcher")
public class PrestoSparkLauncherCommand
{
    @Inject
    public HelpOption helpOption;

    @Inject
    public PrestoSparkVersionOption versionOption = new PrestoSparkVersionOption();

    @Inject
    public PrestoSparkClientOptions clientOptions = new PrestoSparkClientOptions();

    public void run(PrestoSparkDistributionFactory prestoSparkDistributionFactory)
    {
        PrestoSparkDistribution distribution = prestoSparkDistributionFactory.create(clientOptions);

        String query = readFileUtf8(checkFile(new File(clientOptions.file)));

        CachingServiceFactory serviceFactory = new CachingServiceFactory(distribution);
        IPrestoSparkService service = serviceFactory.createService();
        IPrestoSparkQueryExecutionFactory queryExecutionFactory = service.getQueryExecutionFactory();
        PrestoSparkSession session = createSessionInfo(clientOptions);
        IPrestoSparkQueryExecution queryExecution = queryExecutionFactory.create(
                distribution.getSparkContext(),
                session,
                query,
                new DistributionBasedPrestoSparkTaskExecutorFactoryProvider(serviceFactory));

        List<List<Object>> results = queryExecution.execute();

        System.out.println("Rows: " + results.size());
        results.forEach(System.out::println);
    }

    private static PrestoSparkSession createSessionInfo(PrestoSparkClientOptions clientOptions)
    {
        // TODO: add all important session parameters to client options
        return new PrestoSparkSession(
                "test",
                Optional.empty(),
                ImmutableMap.of(),
                Optional.ofNullable(clientOptions.catalog),
                Optional.ofNullable(clientOptions.schema),
                Optional.empty(),
                Optional.empty(),
                ImmutableSet.of(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of(),
                ImmutableMap.of(),
                Optional.empty());
    }

    private static IPrestoSparkServiceFactory createServiceFactory(File directory)
    {
        checkDirectory(directory);
        List<URL> urls = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files != null) {
            sort(files);
        }
        for (File file : files) {
            try {
                urls.add(file.toURI().toURL());
            }
            catch (MalformedURLException e) {
                throw new UncheckedIOException(e);
            }
        }
        PrestoSparkLoader prestoSparkLoader = new PrestoSparkLoader(
                urls,
                PrestoSparkLauncher.class.getClassLoader(),
                asList("org.apache.spark.", "com.facebook.presto.spark.classloader_interface.", "scala."));
        ServiceLoader<IPrestoSparkServiceFactory> serviceLoader = ServiceLoader.load(IPrestoSparkServiceFactory.class, prestoSparkLoader);
        return serviceLoader.iterator().next();
    }

    public static class CachingServiceFactory
            implements Serializable
    {
        private static IPrestoSparkService service;

        private static String currentPackagePath;
        private static Map<String, String> currentConfigProperties;
        private static Map<String, Map<String, String>> currentCatalogProperties;

        private final PackageSupplier packageSupplier;
        private final Map<String, String> configProperties;
        private final Map<String, Map<String, String>> catalogProperties;

        public CachingServiceFactory(PrestoSparkDistribution distribution)
        {
            requireNonNull(distribution, "distribution is null");
            this.packageSupplier = distribution.getPackageSupplier();
            this.configProperties = distribution.getConfigProperties();
            this.catalogProperties = distribution.getCatalogProperties();
        }

        public IPrestoSparkService createService()
        {
            synchronized (CachingServiceFactory.class) {
                if (service == null) {
                    currentPackagePath = checkDirectory(packageSupplier.getPrestoSparkPackageDirectory()).getAbsolutePath();
                    currentConfigProperties = configProperties;
                    currentCatalogProperties = catalogProperties;

                    File pluginsDirectory = checkDirectory(new File(currentPackagePath, "plugin"));
                    PrestoSparkConfiguration configuration = new PrestoSparkConfiguration(configProperties, pluginsDirectory.getAbsolutePath(), catalogProperties);
                    IPrestoSparkServiceFactory serviceFactory = createServiceFactory(checkDirectory(new File(currentPackagePath, "lib")));
                    service = serviceFactory.createService(configuration);
                }
                checkEquals("packagePath", currentPackagePath, packageSupplier.getPrestoSparkPackageDirectory().getAbsolutePath());
                checkEquals("configProperties", currentConfigProperties, configProperties);
                checkEquals("catalogProperties", currentCatalogProperties, catalogProperties);
                return service;
            }
        }

        public static void checkEquals(String name, Object first, Object second)
        {
            if (!Objects.equals(first, second)) {
                throw new IllegalStateException(format("%s is different: %s != %s", name, first, second));
            }
        }
    }

    public static class DistributionBasedPrestoSparkTaskExecutorFactoryProvider
            implements PrestoSparkTaskExecutorFactoryProvider
    {
        private final CachingServiceFactory serviceFactory;

        public DistributionBasedPrestoSparkTaskExecutorFactoryProvider(CachingServiceFactory serviceFactory)
        {
            this.serviceFactory = requireNonNull(serviceFactory, "serviceFactory is null");
        }

        @Override
        public IPrestoSparkTaskExecutorFactory get()
        {
            return serviceFactory.createService().getTaskExecutorFactory();
        }
    }
}

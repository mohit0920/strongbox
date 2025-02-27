package org.carlspring.strongbox.booters;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ILock;
import org.carlspring.strongbox.configuration.Configuration;
import org.carlspring.strongbox.configuration.ConfigurationManager;
import org.carlspring.strongbox.providers.layout.LayoutProviderRegistry;
import org.carlspring.strongbox.providers.repository.group.GroupRepositorySetCollector;
import org.carlspring.strongbox.repository.RepositoryManagementStrategyException;
import org.carlspring.strongbox.services.RepositoryManagementService;
import org.carlspring.strongbox.storage.Storage;
import org.carlspring.strongbox.storage.repository.Repository;
import org.carlspring.strongbox.storage.repository.RepositoryStatusEnum;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.carlspring.strongbox.util.ThrowingConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author mtodorov
 */
public class StorageBooter
{

    private static final Logger logger = LoggerFactory.getLogger(StorageBooter.class);

    @Inject
    private ConfigurationManager configurationManager;

    @Inject
    private LayoutProviderRegistry layoutProviderRegistry;

    @Inject
    private RepositoryManagementService repositoryManagementService;

    @Inject
    private GroupRepositorySetCollector groupRepositorySetCollector;

    @Inject
    private PropertiesBooter propertiesBooter;

    @Inject
    private HazelcastInstance hazelcastInstance;

    public StorageBooter()
    {
    }

    @PostConstruct
    public void initialize()
            throws IOException, RepositoryManagementStrategyException
    {
        ILock lock = hazelcastInstance.getLock("StorageBooterLock");

        if (lock.tryLock())
        {
            try
            {
                final Configuration configuration = configurationManager.getConfiguration();

                initializeStorages(configuration.getStorages());

                Collection<Repository> repositories = getRepositoriesHierarchy(configuration.getStorages());

                if (!repositories.isEmpty())
                {
                    logger.info(" -> Initializing repositories...");
                }

                repositories.forEach(ThrowingConsumer.unchecked(this::initializeRepository));
            }
            finally
            {
                lock.unlock();
            }
        }
        else
        {
            logger.debug("Failed to initialize the repositories. Another JVM may have already done this.");
        }
    }


    private void initializeStorages(final Map<String, Storage> storages)
            throws IOException
    {
        logger.info("Running Strongbox storage booter...");
        logger.info(" -> Creating storage directory skeleton...");

        for (Map.Entry<String, Storage> stringStorageEntry : storages.entrySet())
        {
            initializeStorage(stringStorageEntry.getValue());
        }

    }

    private void initializeStorage(Storage storage)
            throws IOException
    {
        logger.info("  * Initializing " + storage.getId() + "...");
    }

    private void initializeRepository(Repository repository)
            throws IOException, RepositoryManagementStrategyException
    {
        logger.info("  * Initializing " + repository.getStorage().getId() + ":" + repository.getId() + "...");

        if (layoutProviderRegistry.getProvider(repository.getLayout()) == null)
        {
            logger.error(String.format("Failed to resolve layout [%s] for repository [%s].",
                                       repository.getLayout(),
                                       repository.getId()));
            return;
        }

        repositoryManagementService.createRepository(repository.getStorage().getId(), repository.getId());

        if (RepositoryStatusEnum.IN_SERVICE.getStatus().equals(repository.getStatus()))
        {
            repositoryManagementService.putInService(repository.getStorage().getId(), repository.getId());
        }
    }

    private Collection<Repository> getRepositoriesHierarchy(final Map<String, Storage> storages)
    {
        final Map<String, Repository> repositoriesHierarchy = new LinkedHashMap<>();
        for (final Storage storage : storages.values())
        {
            for (final Repository repository : storage.getRepositories().values())
            {
                addRepositoriesByChildrenFirst(repositoriesHierarchy, repository);
            }
        }

        return repositoriesHierarchy.values();
    }

    private void addRepositoriesByChildrenFirst(final Map<String, Repository> repositoriesHierarchy,
                                                final Repository repository)
    {
        if (!repository.isGroupRepository())
        {
            repositoriesHierarchy.putIfAbsent(repository.getId(), repository);

            return;
        }

        groupRepositorySetCollector.collect(repository, true)
                                   .stream().forEach(r -> addRepositoriesByChildrenFirst(repositoriesHierarchy, r));

        repositoriesHierarchy.putIfAbsent(repository.getId(), repository);
    }

    public RepositoryManagementService getRepositoryManagementService()
    {
        return repositoryManagementService;
    }

    public void setRepositoryManagementService(RepositoryManagementService repositoryManagementService)
    {
        this.repositoryManagementService = repositoryManagementService;
    }

}

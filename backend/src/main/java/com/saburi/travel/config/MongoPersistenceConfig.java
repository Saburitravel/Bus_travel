package com.saburi.travel.config;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import com.mongodb.client.MongoClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.lang.reflect.Field;

/** MongoDB persistence support. Long IDs are generated from an atomic sequence collection. */
@Configuration
@EnableTransactionManagement
public class MongoPersistenceConfig {
    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory);
    }

    @Bean
    BeforeConvertCallback<Object> numericIdSequenceCallback(
            MongoClient mongoClient,
            @Value("${spring.mongodb.database:saburi_travel}") String databaseName) {
        return (entity, collection) -> {
            try {
                Field id = findIdField(entity.getClass());
                if (id == null || id.getType() != Long.class) return entity;
                id.setAccessible(true);
                if (id.get(entity) != null) return entity;
                Document sequence = mongoClient.getDatabase(databaseName).getCollection("_sequences").findOneAndUpdate(
                    new Document("_id", collection),
                    new Document("$inc", new Document("value", 1L)),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
                if (sequence == null) throw new IllegalStateException("Could not allocate ID for " + collection);
                id.set(entity, sequence.getLong("value"));
                return entity;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not assign MongoDB ID", e);
            }
        };
    }

    private static Field findIdField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) if (f.getName().equals("id")) return f;
        }
        return null;
    }
}

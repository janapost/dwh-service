// =====================================================================================
// Индексы журнала AppsFlyer в базе dwh-service (UPM-2661). Те же, что в client-service:
// auto-index-creation выключен, Spring индексы не создаёт.
//
// Запуск — до включения консьюмера (S2S_CONSUMER_ENABLED=true):
//   mongosh "<dwh-service uri>" --quiet s2s-indexes.js
// Повторный запуск безопасен: createIndex с тем же определением ничего не делает.
// Коллекцию jpevent (Event_dwh_service) и её индексы создаёт сам сервис при старте.
// =====================================================================================

const DWH_DB = "dwh-service";
const dwhDb = db.getSiblingDB(DWH_DB);

const events = dwhDb.getCollection("AppsflyerEventPO");

// дедупликация: insert до отправки, DuplicateKeyException = событие уже было. У старых строк ключа нет — partial.
events.createIndex(
    {dedupKey: 1},
    {name: "dedupKey_unique", unique: true, partialFilterExpression: {dedupKey: {$type: "string"}}}
);
// ретрай: FAILED и зависшие PENDING с attempt < max
events.createIndex({status: 1, attempt: 1}, {name: "status_attempt"});
// NO_IDENTITY → ретрай, когда придёт AppsFlyer ID клиента
events.createIndex({jpid: 1, status: 1}, {name: "jpid_status"});
// af_purchase: старые строки по одному заказу
events.createIndex({orderId: 1, eventName: 1, status: 1}, {name: "orderId_eventName_status"});
// очистка SENT / SKIPPED старше срока хранения (запрос очистки требует именно этот индекс — hint)
events.createIndex({status: 1, createTime: 1}, {name: "status_createTime"});

printjson(events.getIndexes().map(i => i.name));

package id.primawash.api.order

import id.primawash.api.common.DomainException
import id.primawash.api.common.Pagination
import id.primawash.api.common.Requests
import id.primawash.api.common.ValidationException
import id.primawash.api.common.toApi
import id.primawash.api.plugins.receiveIdempotent
import id.primawash.api.plugins.respondIdempotent
import id.primawash.api.plugins.staffPrincipal
import id.primawash.api.plugins.storedResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** PRD §8.6 and §11.2. Branch and role always come from the session, never from the body. */
fun Route.orderRoutes(
    orders: OrderService,
    sync: OrderSyncService,
) {
    route("/orders") {
        get {
            val principal = call.staffPrincipal()
            val params = call.request.queryParameters
            // PRD §8.6: a cashier's branchId is ignored — always the token branch. Owner: a named branch,
            // every branch with scope=all, else the branch of this tablet.
            val branchIds =
                when {
                    !principal.isOwner -> listOf(principal.branchId)
                    params["branchId"] != null -> listOf(Requests.uuid(params["branchId"], "branchId"))
                    params["scope"] == "all" -> null
                    else -> listOf(principal.branchId)
                }
            val page =
                orders.list(
                    branchIds = branchIds,
                    status = params["status"]?.let { Requests.enum<OrderStatus>(it, "status") },
                    q = params["q"],
                    from = Requests.date(params["from"], "from"),
                    to = Requests.date(params["to"], "to"),
                    limit = Pagination.limit(params["limit"]),
                    cursor = params["cursor"],
                )
            call.respond(page.toDto())
        }

        post {
            val principal = call.staffPrincipal()
            val (body, idempotency) = call.receiveIdempotent<PlaceOrderRequest>()
            val command = body.toCommand()
            val result =
                orders.place(principal, command, idempotency) { storedResponse(HttpStatusCode.Created, it.toDto()) }
            call.respondIdempotent(result, HttpStatusCode.Created) { it.toDto() }
        }

        post("/sync") {
            val principal = call.staffPrincipal()
            val (body, idempotency) = call.receiveIdempotent<SyncOrdersRequest>()
            val transactions = Requests.required(body.transactions, "transactions")
            if (transactions.size > OrderSyncService.MAX_TRANSACTIONS) {
                throw ValidationException("Maksimal ${OrderSyncService.MAX_TRANSACTIONS} transaksi per sync.")
            }
            val result =
                sync.sync(principal, transactions.map { it.toTransaction() }, idempotency) {
                    storedResponse(HttpStatusCode.OK, it.toDto())
                }
            call.respondIdempotent(result, HttpStatusCode.OK) { it.toDto() }
        }

        get("/next-number") {
            val principal = call.staffPrincipal()
            val next = orders.nextNumber(principal.branchId)
            call.respond(NextNumberResponse(next.number, next.businessDate.toString()))
        }

        get("/{id}") {
            val principal = call.staffPrincipal()
            val detail = orders.detail(principal, Requests.uuid(call.parameters["id"], "id"))
            call.respond(OrderDetailResponse(detail.order.toDto(), detail.events.map { it.toDto() }))
        }

        post("/{id}/advance") {
            val principal = call.staffPrincipal()
            val id = Requests.uuid(call.parameters["id"], "id")
            val raw = Requests.required(call.receive<AdvanceOrderRequest>().fromStatus, "fromStatus")
            val advanced = orders.advance(principal, id, Requests.enum<OrderStatus>(raw, "fromStatus"))
            call.respond(
                AdvanceOrderResponse(advanced.outcome.name, advanced.order.toDto(), advanced.notificationQueued),
            )
        }
    }
}

// ---- Request parsing (format only — business rules belong to the services) ----------------------------

private fun PlaceOrderRequest.toCommand(): PlaceOrderCommand {
    val items =
        items.orEmpty().mapIndexed { index, item ->
            CartItem(
                serviceId = Requests.uuid(item.serviceId, "items[$index].serviceId"),
                qty = Requests.decimal(item.qty, "items[$index].qty"),
                unitPrice = Requests.required(item.unitPrice, "items[$index].unitPrice"),
            )
        }
    return PlaceOrderCommand(
        clientTxId = Requests.uuid(clientTxId, "clientTxId"),
        customerId = Requests.uuidOrNull(customerId, "customerId"),
        items = items,
        rewardId = Requests.uuidOrNull(rewardId, "rewardId"),
        payment = Requests.enum(payment, "payment"),
        note = note,
        expectedTotal = Requests.required(expectedTotal, "expectedTotal"),
    )
}

/** A malformed offline transaction is rejected on its own, so the rest of the queue still syncs. */
private fun SyncTransactionRequest.toTransaction(): SyncTransaction {
    val clientTx = runCatching { Requests.uuidOrNull(clientTxId, "clientTxId") }.getOrNull()
    return try {
        SyncTransaction(
            clientTxId = Requests.uuid(clientTxId, "clientTxId"),
            capturedAt = Requests.instant(capturedAt, "capturedAt"),
            shiftId = Requests.uuidOrNull(shiftId, "shiftId"),
            staffId = Requests.uuidOrNull(staffId, "staffId"),
            customerId = Requests.uuidOrNull(customerId, "customerId"),
            newCustomer =
                newCustomer?.let {
                    NewOfflineCustomer(
                        Requests.uuidOrNull(it.id, "newCustomer.id"),
                        it.name,
                        it.phone,
                        it.optIn ?: false,
                    )
                },
            items =
                items.orEmpty().mapIndexed { index, item ->
                    SyncItem(
                        serviceId = Requests.uuid(item.serviceId, "items[$index].serviceId"),
                        qty = Requests.decimal(item.qty, "items[$index].qty"),
                        unitPrice = Requests.required(item.unitPrice, "items[$index].unitPrice"),
                    )
                },
            hasReward = rewardId != null,
            payment = Requests.enum(payment, "payment"),
            note = note,
        )
    } catch (error: DomainException) {
        SyncTransaction(clientTx, null, null, null, null, null, emptyList(), false, null, null, invalid = error)
    }
}

// ---- Response mapping --------------------------------------------------------------------------------

fun OrderRecord.toDto() =
    OrderDto(
        id = id.toString(),
        number = number,
        branchId = branchId.toString(),
        businessDate = businessDate.toString(),
        customerId = customerId?.toString(),
        customerName = customerName,
        customerPhone = customerPhone,
        items =
            items.map {
                OrderItemDto(it.serviceId.toString(), it.name, it.qty.toDouble(), it.unit, it.unitPrice, it.subtotal)
            },
        note = note,
        subtotal = subtotal,
        discount = discount,
        total = total,
        rewardId = rewardId?.toString(),
        rewardName = rewardName,
        redeemedPoints = redeemedPoints,
        earnedPoints = earnedPoints,
        payment = payment.name,
        status = status.name,
        waStatus = waStatus.name,
        capturedAt = capturedAt.toApi(),
        createdAt = createdAt.toApi(),
        statusChangedAt = statusChangedAt.toApi(),
        shiftId = shiftId.toString(),
        staffId = staffId.toString(),
        source = source.name,
        flags = flags.map { it.name },
        version = version,
    )

/** The API shape of an order as `details.order` of a conflict (`STATUS_CHANGED`, `DUPLICATE_TRANSACTION`). */
internal fun OrderRecord.toDetailsJson(): JsonObject =
    Json.encodeToJsonElement(OrderDto.serializer(), toDto()).jsonObject

private fun OrderPage.toDto() =
    OrderPageResponse(
        items = items.map { it.toDto() },
        nextCursor = nextCursor,
        counts =
            OrderCountsDto(
                all = counts.values.sum(),
                diterima = counts[OrderStatus.DITERIMA] ?: 0,
                proses = counts[OrderStatus.PROSES] ?: 0,
                siap = counts[OrderStatus.SIAP] ?: 0,
                selesai = counts[OrderStatus.SELESAI] ?: 0,
            ),
    )

private fun OrderEventRecord.toDto() =
    OrderEventDto(id, type, fromStatus, toStatus, staffId?.toString(), deviceId?.toString(), createdAt.toApi(), payload)

private fun PlacedOrder.toDto() =
    PlaceOrderResponse(order.toDto(), customer?.let { CustomerBalanceDto(it.id.toString(), it.points, it.visits) })

private fun SyncOutcome.toDto() =
    SyncOrdersResponse(
        results =
            results.map { result ->
                SyncResultDto(
                    clientTxId = result.clientTxId?.toString(),
                    status = result.status.name,
                    order = result.order?.toDto(),
                    error = result.error?.let { SyncErrorDto(it.code, it.message) },
                    customerIdMapping =
                        result.customerIdMapping?.let { (client, server) ->
                            CustomerIdMappingDto(client.toString(), server.toString())
                        },
                )
            },
        summary = SyncSummaryDto(createdCount, duplicateCount, rejectedCount, total, points),
    )

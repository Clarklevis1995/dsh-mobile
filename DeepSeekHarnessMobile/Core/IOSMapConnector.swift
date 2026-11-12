import Foundation
import MapKit
import CoreLocation

/// 地图服务与位置共用连接器开关，参数在手机端再次校验。
@MainActor
enum IOSMapConnector {
    static let tools = ["location.precise", "maps.search", "maps.geocode", "maps.reverse-geocode", "maps.directions", "maps.open"] + PhoneMapMarkerStore.tools
    static func number(_ value: Any?, range: ClosedRange<Double>) -> Double? {
        guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(), value.doubleValue.isFinite, range.contains(value.doubleValue) else { return nil }
        return value.doubleValue
    }
    static func coordinate(_ args: [String: Any], latitude: String = "latitude", longitude: String = "longitude") throws -> CLLocationCoordinate2D {
        guard let lat = number(args[latitude], range: -90...90), let lon = number(args[longitude], range: -180...180) else { throw PhoneConnectorFailure(code: "invalid_request") }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
    private static func text(_ value: Any?, max: Int) -> Bool {
        guard let value = value as? String else { return false }
        return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && value.utf16.count <= max
    }
    private static func integer(_ value: Any, range: ClosedRange<Double>) -> Bool {
        guard let value = number(value, range: range) else { return false }
        return value.rounded() == value
    }
    static func validate(_ tool: String, _ args: [String: Any]) throws {
        let fields: Set<String>
        switch tool {
        case "location.precise": fields = ["maxAgeSeconds"]
        case "maps.search": fields = ["query", "latitude", "longitude", "radiusMeters", "limit"]
        case "maps.geocode": fields = ["address", "limit"]
        case "maps.reverse-geocode": fields = ["latitude", "longitude"]
        case "maps.directions": fields = ["startLatitude", "startLongitude", "endLatitude", "endLongitude", "transport"]
        case "maps.open": fields = ["latitude", "longitude", "name", "mode", "transport"]
        default: throw PhoneConnectorFailure(code: "tool_unsupported")
        }
        guard Set(args.keys).isSubset(of: fields) else { throw PhoneConnectorFailure(code: "invalid_request") }
        if let age = args["maxAgeSeconds"], !integer(age, range: 1...300) { throw PhoneConnectorFailure(code: "invalid_request") }
        if ["maps.search", "maps.geocode"].contains(tool) {
            guard text(args[tool == "maps.search" ? "query" : "address"], max: 200) else { throw PhoneConnectorFailure(code: "invalid_request") }
            if let limit = args["limit"], !integer(limit, range: 1...(tool == "maps.search" ? 20 : 5)) { throw PhoneConnectorFailure(code: "invalid_request") }
        }
        if tool == "maps.search" {
            if args["latitude"] != nil || args["longitude"] != nil { _ = try coordinate(args) }
            if let radius = args["radiusMeters"] {
                guard args["latitude"] != nil, number(radius, range: 100...100000) != nil else { throw PhoneConnectorFailure(code: "invalid_request") }
            }
        }
        if ["maps.reverse-geocode", "maps.open"].contains(tool) { _ = try coordinate(args) }
        if tool == "maps.directions" {
            _ = try coordinate(args, latitude: "startLatitude", longitude: "startLongitude")
            _ = try coordinate(args, latitude: "endLatitude", longitude: "endLongitude")
            if let mode = args["transport"] as? String { guard ["driving", "walking"].contains(mode) else { throw PhoneConnectorFailure(code: "invalid_request") } }
            else if args["transport"] != nil { throw PhoneConnectorFailure(code: "invalid_request") }
        }
        if tool == "maps.open" {
            if let name = args["name"], !text(name, max: 200) { throw PhoneConnectorFailure(code: "invalid_request") }
            let mode = args["mode"] as? String ?? "show"
            guard args["mode"] == nil || args["mode"] is String, ["show", "directions"].contains(mode) else { throw PhoneConnectorFailure(code: "invalid_request") }
            if let transport = args["transport"] {
                guard mode == "directions", let transport = transport as? String, ["driving", "walking", "transit"].contains(transport) else { throw PhoneConnectorFailure(code: "invalid_request") }
            }
        }
    }
    static func locationResult(_ sample: CLLocation, precise: Bool, maxAge: Double = 60, now: Date = Date()) throws -> [String: Any] {
        guard CLLocationCoordinate2DIsValid(sample.coordinate), sample.horizontalAccuracy >= 0, abs(sample.timestamp.timeIntervalSince(now)) <= maxAge else { throw PhoneConnectorFailure(code: "data_unavailable") }
        return ["latitude": precise ? sample.coordinate.latitude : (sample.coordinate.latitude * 100).rounded() / 100,
                "longitude": precise ? sample.coordinate.longitude : (sample.coordinate.longitude * 100).rounded() / 100,
                "precision": precise ? "precise" : "coarse", "horizontalAccuracyMeters": precise ? sample.horizontalAccuracy : max(sample.horizontalAccuracy, 1500),
                "sampledAt": ISO8601DateFormatter().string(from: sample.timestamp)]
    }
    static func bounded(_ value: String, max: Int = 200) -> String { String(decoding: value.utf16.prefix(max), as: UTF16.self) }
    private static func place(_ placemark: CLPlacemark, name: String? = nil) -> [String: Any]? {
        guard let coordinate = placemark.location?.coordinate, CLLocationCoordinate2DIsValid(coordinate) else { return nil }
        let address = [placemark.country, placemark.administrativeArea, placemark.locality, placemark.subLocality, placemark.thoroughfare, placemark.subThoroughfare]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        return ["name": bounded(name ?? placemark.name ?? address), "latitude": coordinate.latitude, "longitude": coordinate.longitude, "address": bounded(address, max: 400)]
    }
    private static func items(_ rows: [[String: Any]], limit: Int) -> [String: Any] {
        ["items": Array(rows.prefix(limit)), "truncated": rows.count > limit, "source": "apple-maps"]
    }
    static func execute(_ tool: String, _ args: [String: Any]) async throws -> [String: Any] {
        try validate(tool, args)
        if tool == "maps.open" { return try handoffReceipt(args) }
        do {
            let result: [String: Any]
            switch tool {
            case "maps.search":
                let request = MKLocalSearch.Request(); request.naturalLanguageQuery = args["query"] as? String
                request.resultTypes = [.address, .pointOfInterest]
                let center = args["latitude"] == nil ? nil : try coordinate(args)
                let radius = args["radiusMeters"] as? Double ?? 5000
                if let center { request.region = MKCoordinateRegion(center: center, latitudinalMeters: radius * 2, longitudinalMeters: radius * 2) }
                let search = MKLocalSearch(request: request)
                let response = try await withTaskCancellationHandler { try await search.start() } onCancel: { Task { @MainActor in search.cancel() } }
                let rows = response.mapItems.filter { item in
                    guard let center else { return true }
                    return CLLocation(latitude: center.latitude, longitude: center.longitude).distance(from: CLLocation(latitude: item.placemark.coordinate.latitude, longitude: item.placemark.coordinate.longitude)) <= radius
                }.compactMap { place($0.placemark, name: $0.name) }
                result = items(rows, limit: args["limit"] as? Int ?? 10)
            case "maps.geocode", "maps.reverse-geocode":
                let geocoder = CLGeocoder()
                let places: [CLPlacemark]
                do {
                    places = try await withTaskCancellationHandler {
                        if tool == "maps.geocode" { return try await geocoder.geocodeAddressString(args["address"] as! String) }
                        let point = try coordinate(args)
                        return try await geocoder.reverseGeocodeLocation(CLLocation(latitude: point.latitude, longitude: point.longitude))
                    } onCancel: { Task { @MainActor in geocoder.cancelGeocode() } }
                } catch let error as CLError where error.code == .geocodeFoundNoResult { return items([], limit: 1) }
                result = items(places.compactMap { place($0) }, limit: args["limit"] as? Int ?? (tool == "maps.geocode" ? 3 : 5))
            case "maps.directions":
                let request = MKDirections.Request()
                request.source = MKMapItem(placemark: MKPlacemark(coordinate: try coordinate(args, latitude: "startLatitude", longitude: "startLongitude")))
                request.destination = MKMapItem(placemark: MKPlacemark(coordinate: try coordinate(args, latitude: "endLatitude", longitude: "endLongitude")))
                let transport = args["transport"] as? String ?? "driving"
                request.transportType = transport == "walking" ? .walking : .automobile; request.requestsAlternateRoutes = true
                let directions = MKDirections(request: request)
                let response = try await withTaskCancellationHandler { try await directions.calculate() } onCancel: { Task { @MainActor in directions.cancel() } }
                let routes: [[String: Any]] = response.routes.prefix(3).map { route in
                    let steps = route.steps.filter { !$0.instructions.isEmpty }
                    return ["name": bounded(route.name), "distanceMeters": route.distance, "expectedTravelSeconds": route.expectedTravelTime,
                            "steps": steps.prefix(40).map { ["instruction": bounded($0.instructions, max: 160), "distanceMeters": $0.distance] }, "stepsTruncated": steps.count > 40]
                }
                result = ["routes": routes, "transport": transport, "truncated": response.routes.count > 3, "estimatedAt": ISO8601DateFormatter().string(from: Date()), "source": "apple-maps"]
            default: throw PhoneConnectorFailure(code: "tool_unsupported")
            }
            try Task.checkCancellation()
            return result
        } catch is CancellationError { throw CancellationError() }
        catch let error as PhoneConnectorFailure { throw error }
        catch { try Task.checkCancellation(); throw PhoneConnectorFailure(code: "maps_service_unavailable") }
    }
    static func handoffReceipt(_ args: [String: Any]) throws -> [String: Any] {
        try validate("maps.open", args)
        var result: [String: Any] = ["status": "handoff-requested", "latitude": args["latitude"]!, "longitude": args["longitude"]!, "name": args["name"] as? String ?? String(localized: "目的地"), "mode": args["mode"] as? String ?? "show"]
        if result["mode"] as? String == "directions" { result["transport"] = args["transport"] as? String ?? "driving" }
        return result
    }
    /// 回执先交给控制连接，再调用此方法，避免跳出 App 被误报为调用失败。
    static func open(_ args: [String: Any]) -> Bool {
        guard (try? validate("maps.open", args)) != nil, let coordinate = try? coordinate(args) else { return false }
        let destination = MKMapItem(placemark: MKPlacemark(coordinate: coordinate)); destination.name = args["name"] as? String ?? String(localized: "目的地")
        var launch: [String: Any] = [:]
        if args["mode"] as? String == "directions" {
            launch[MKLaunchOptionsDirectionsModeKey] = ["driving": MKLaunchOptionsDirectionsModeDriving, "walking": MKLaunchOptionsDirectionsModeWalking, "transit": MKLaunchOptionsDirectionsModeTransit][args["transport"] as? String ?? "driving"]!
        }
        return destination.openInMaps(launchOptions: launch)
    }
}

import Foundation
@testable import IFATCCompanion

enum TestSupport {
    static func context(callsignAirline: String = "United",
                        flightNumber: String = "598",
                        destination: String = "KMSP",
                        cruise: Int = 37000,
                        runway: String = "17R") -> ATCContext {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let cs = engine.callsign(airline: callsignAirline, flightNumber: flightNumber, fallback: "")
        var plan = FlightPlan()
        plan.airline = callsignAirline
        plan.flightNumber = flightNumber
        plan.destination = destination
        plan.cruiseAltitude = cruise
        return ATCContext(
            callsign: cs,
            plan: plan,
            assignedAltitude: 5000,
            cruiseAltitude: cruise,
            initialClimbAltitude: 5000,
            windDirection: 180,
            windSpeed: 8,
            squawk: "4271",
            runway: runway,
            taxiway: "Alpha",
            crossingRunway: nil,
            parkingTaxiway: "Bravo",
            approachName: "the ILS",
            departureFrequency: 124.300,
            centerFrequency: 132.450,
            approachFrequency: 119.700,
            towerFrequency: 118.300,
            groundFrequency: 121.800)
    }
}

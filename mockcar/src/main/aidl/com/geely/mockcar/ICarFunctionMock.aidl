package com.geely.mockcar;

// Cross-app AIDL surface, the stand-in for the ECARX car function API. This file is
// byte-identical in :app (app/src/main/aidl/...) and :mockcar — keep them in sync.
interface ICarFunctionMock {
    /** Current stored value for a property+zone; 0 when never set ("everything off"). */
    int getFunctionValue(int propertyId, int areaId);

    /** Stores a value; false when the mock rejects it (unknown property or illegal value). */
    boolean setFunctionValue(int propertyId, int areaId, int value);

    /** Liveness probe. */
    boolean isAlive();
}

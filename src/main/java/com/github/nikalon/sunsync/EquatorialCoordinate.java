package com.github.nikalon.sunsync;

class EquatorialCoordinate {
    // WARNING! No data validation is performed
    public final double rightAscension;
    public final double declination;

    public EquatorialCoordinate(double rightAscension, double declination) {
        this.rightAscension = rightAscension;
        this.declination = declination;
    }
}
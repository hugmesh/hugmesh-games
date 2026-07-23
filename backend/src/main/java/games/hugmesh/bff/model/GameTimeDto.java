package games.hugmesh.bff.model;

public record GameTimeDto(int dayNumber, int hour, int minute) {
    public static GameTimeDto from(GameTimeState s) {
        return new GameTimeDto(s.getDayNumber(), s.getHour(), s.getMinute());
    }

    public String formatted() {
        return String.format("Day %d  %02d:%02d", dayNumber, hour, minute);
    }
}

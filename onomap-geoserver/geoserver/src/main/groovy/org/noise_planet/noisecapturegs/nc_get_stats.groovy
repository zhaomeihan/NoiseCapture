/*
 * This file is part of the NoiseCapture application and OnoMap system.
 *
 * The 'OnoMaP' system is led by Lab-STICC and Ifsttar and generates noise maps via
 * citizen-contributed noise data.
 *
 * This application is co-funded by the ENERGIC-OD Project (European Network for
 * Redistributing Geospatial Information to user Communities - Open Data). ENERGIC-OD
 * (http://www.energic-od.eu/) is partially funded under the ICT Policy Support Programme (ICT
 * PSP) as part of the Competitiveness and Innovation Framework Programme by the European
 * Community. The application work is also supported by the French geographic portal GEOPAL of the
 * Pays de la Loire region (http://www.geopal.org).
 *
 * Copyright (C) 2007-2016 - IFSTTAR - LAE
 * Lab-STICC – CNRS UMR 6285 Equipe DECIDE Vannes
 *
 * NoiseCapture is a free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of
 * the License, or(at your option) any later version. NoiseCapture is distributed in the hope that
 * it will be useful,but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.You should have received a copy of the GNU General Public License along with this
 * program; if not, write to the Free Software Foundation,Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301  USA or see For more information,  write to Ifsttar,
 * 14-20 Boulevard Newton Cite Descartes, Champs sur Marne F-77447 Marne la Vallee Cedex 2 FRANCE
 *  or write to scientific.computing@ifsttar.fr
 */

package org.noise_planet.noisecapturegs

import geoserver.GeoServer
import geoserver.catalog.Store
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.geotools.jdbc.JDBCDataStore
import org.springframework.security.core.context.SecurityContextHolder

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneRulesException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

title = 'nc_gt_stats'
description = 'Build statistics from database'

inputs = [:]

outputs = [
        result: [name: 'result', title: 'Statistics as JSON', type: String.class]
]


static
def getStatistics(Connection connection) {
    def sql = new Sql(connection)

    def statistics = [:];

    // New contributors since last week:
    statistics["week_new_contributors"] = sql.firstRow("select count(*) cpt from noisecapture_user where" +
            " extract(epoch from date_creation) > extract(epoch from now()) - 3600 * 24 * 7").cpt as Integer;
    // Number of tracks since last week:
    statistics["week_new_tracks_count"] = sql.firstRow("select count(*) cpt from noisecapture_track where" +
            " extract(epoch from record_utc) > extract(epoch from now()) - 3600 * 24 * 7").cpt as Integer;
    // Duration (JJ:HH:MM:SS) since last week:
    statistics["week_new_tracks_duration"] = sql.firstRow("select sum(time_length) timelen from noisecapture_track where" +
            " extract(epoch from record_utc) > extract(epoch from now()) - 3600 * 24 * 7").timelen as Integer;

    // Approximate number of contributors
    statistics["total_contributors"] = sql.firstRow("select count(*) cpt from noisecapture_user").cpt as Integer;
    // Total number of tracks
    statistics["total_tracks"] = sql.firstRow("select count(*) cpt from noisecapture_track").cpt as Integer;
    // Duration (JJ:HH:MM:SS)
    statistics["total_tracks_duration"] = sql.firstRow("select sum(time_length) timelen from noisecapture_track").timelen as Long;

    // Tags
    def tags = [];
    sql.eachRow("select tag_name, count(*) cpt from noisecapture_tag t, noisecapture_track_tag tt where t.pk_tag = tt.pk_tag group by tag_name order by cpt desc;") {
        record ->
            tags.add([text:record.tag_name, weight : record.cpt as Integer])
    }
    statistics["tags"] = tags;

    // Countries
    def names = []
    def track_count = []
    def track_length = []
    sql.eachRow("select name_0, count(t.*) nb_track, sum(t.time_length) total_length  from GADM28 ga, (SELECT nt.pk_track, ST_SETSRID(ST_EXTENT(ST_MAKEPOINT(ST_X(the_geom),ST_Y(the_geom))), 4326) the_geom, time_length from noisecapture_point np, noisecapture_track nt where not ST_ISEMPTY(the_geom) and nt.pk_track = np.pk_track  group by nt.pk_track, nt.time_length) t where t.the_geom && ga.the_geom and st_intersects(st_centroid(t.the_geom), ga.the_geom) group by name_0 order by nb_track desc;") {
        record ->
            names.add(record.name_0)
            track_count.add(record.nb_track as Long)
            track_length.add(record.total_length as Long)
    }
    statistics["countries"] = [names:names, total_tracks : track_count, track_length:track_length];

    def distribution_measure_length = [:]
    def labels = []
    def values = []
    sql.eachRow("select time_length::int / power(10, length(time_length::varchar) - 1)::int * power(10, length(time_length::varchar) - 1)::int as fromValue,(time_length::int / power(10, length(time_length::varchar) - 1)::int + 1) * power(10, length(time_length::varchar) - 1)::int toValue, count(*) total_tracks FROM noisecapture_track t group by fromValue, toValue order by fromValue;") {
        record ->
            labels.add(record.fromValue+" s-"+record.toValue+" s")
            values.add(record.total_tracks as Long)
    }
    distribution_measure_length["ranges"] = labels
    distribution_measure_length["total_tracks"] = values

    statistics["distribution_measurements"] = distribution_measure_length;

    def tag_count = []
    def track_tag_count = []

    sql.eachRow("select cpt_tags, count(*) nb_track from (SELECT nt.pk_track, count(pk_tag) cpt_tags FROM noisecapture_track nt LEFT JOIN noisecapture_track_tag ntt USING(pk_track) group by pk_track) track_tag group by cpt_tags order by cpt_tags") {
        record ->
            tag_count.add(record.cpt_tags as Integer)
            track_tag_count.add(record.nb_track as Long)
    }

    statistics["tags_per_track"] = [tag_count : tag_count, track_count : track_tag_count];

    def week_tracks_date = []
    def week_tracks_count = []
    def week_track_length = []

    sql.eachRow("select to_char(record_utc, 'YYYY-WW') year_week, count(*) nb_tracks, sum(time_length) sum_length from noisecapture_track group by  year_week order by year_week desc limit 7") {
        record ->
            week_tracks_date.add(record.year_week as String)
            week_tracks_count.add(record.nb_tracks as Long)
            week_track_length.add(record.sum_length as Long)
    }

    statistics["week_tracks"] = [week_tracks_date : week_tracks_date, week_tracks_count : week_tracks_count, week_track_length:week_track_length];

    //TODO contributions dernieres semaines (manque le where)
    // select name_0, to_char(record_utc, 'YYYY-WW') year_week, count(t.*) nb_track, sum(t.time_length) total_length  from GADM28 ga, (SELECT nt.pk_track, ST_SETSRID(ST_EXTENT(ST_MAKEPOINT(ST_X(the_geom),ST_Y(the_geom))), 4326) the_geom, time_length, record_utc from noisecapture_point np, noisecapture_track nt where  extract(epoch from record_utc) > extract(epoch from now()) - 3600 * 24 * 7 * 7 and not ST_ISEMPTY(the_geom) and nt.pk_track = np.pk_track  group by nt.pk_track, nt.time_length) t where t.the_geom && ga.the_geom and st_intersects(st_centroid(t.the_geom), ga.the_geom) group by name_0, year_week order by name_0 asc, year_week desc;

    return statistics;
}

static def Connection openPostgreSQLDataStoreConnection() {
    Store store = new GeoServer().catalog.getStore("postgis")
    JDBCDataStore jdbcDataStore = (JDBCDataStore) store.getDataStoreInfo().getDataStore(null)
    return jdbcDataStore.getDataSource().getConnection()
}

def run(input) {
    // Open PostgreSQL connection
    // Create dump folder
    Connection connection = openPostgreSQLDataStoreConnection()
    try {
        return [result: JsonOutput.toJson(getStatistics(connection))]
    } finally {
        connection.close()
    }
}

/**
 * 
 */
package net.osmand.plus.activities.search;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import android.support.v4.app.Fragment;
import android.support.v7.widget.Toolbar;
import android.view.*;
import net.osmand.data.LatLon;
import net.osmand.data.TransportRoute;
import net.osmand.data.TransportStop;
import net.osmand.plus.OsmAndFormatter;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.TargetPointsHelper.TargetPoint;
import net.osmand.plus.activities.TransportRouteHelper;
import net.osmand.plus.activities.search.SearchActivity.SearchActivityChild;
import net.osmand.plus.resources.TransportIndexRepository;
import net.osmand.plus.resources.TransportIndexRepository.RouteInfoLocation;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;


public class SearchTransportFragment extends Fragment implements SearchActivityChild, OnItemClickListener {

	public static final String SEARCH_LAT = SearchActivity.SEARCH_LAT;
	public static final String SEARCH_LON = SearchActivity.SEARCH_LON;

	private Button searchTransportLevel;

	private TextView searchArea;
	
	private final static int finalZoom = 13;
	private final static int initialZoom = 17;
	private int zoom = initialZoom;
	private ProgressBar progress;

	private LatLon lastKnownMapLocation;
	private LatLon destinationLocation;
	private LatLon selectedDestinationLocation;
	
	private TransportStopAdapter stopsAdapter;
	private TransportRouteAdapter intermediateListAdapater;
	private OsmandSettings settings;
	private View view;
	private AsyncTask<?, ?, ?> asyncTask;

	private OsmandApplication getApplication() {
		return (OsmandApplication) getActivity().getApplication();
	}
	
	public View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container, Bundle savedInstanceState) {
		view = inflater.inflate(R.layout.search_transport, container, false);
		settings = getApplication().getSettings();
		
		searchTransportLevel = (Button) view.findViewById(R.id.SearchTransportLevelButton);
		searchTransportLevel.setText(R.string.search_POI_level_btn);
		
		searchTransportLevel.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (!isRouteCalculated()) {
					if (isSearchFurtherAvailable()) {
						zoom--;
						searchTransport();
					}
				} else {
					intermediateListAdapater.clear();
					intermediateListAdapater.add(null);
					searchTransport();
				}
			}
		});
		searchArea = (TextView) view.findViewById(R.id.SearchAreaText);
		progress = (ProgressBar) view.findViewById(R.id.ProgressBar);
		progress.setVisibility(View.INVISIBLE);
		stopsAdapter = new TransportStopAdapter(new ArrayList<RouteInfoLocation>());
		((ListView) view.findViewById(android.R.id.list)).setAdapter(stopsAdapter);
		((ListView) view.findViewById(android.R.id.list)).setOnItemClickListener(this);
		
		ListView intermediateList = (ListView) view.findViewById(R.id.listView);
		intermediateListAdapater = new TransportRouteAdapter(TransportRouteHelper.getInstance().getRoute());
		intermediateList.setAdapter(intermediateListAdapater);
		
		if(intermediateList.getCount() == 0){
			intermediateListAdapater.add(null);
		}
		setHasOptionsMenu(true);
		return view;
	}
	
	@Override
	public void onDestroy() {
		if (intermediateListAdapater != null) {
			ArrayList<RouteInfoLocation> lastEditedRoute = new ArrayList<RouteInfoLocation>();
			for (int i = 0; i < intermediateListAdapater.getCount(); i++) {
				RouteInfoLocation item = intermediateListAdapater.getItem(i);
				if (item != null) {
					lastEditedRoute.add(item);
				}
			}
			TransportRouteHelper.getInstance().setRoute(lastEditedRoute);
		}
		super.onDestroy();
	}
	
	@Override
	public void onResume() {
		super.onResume();
		Intent intent = getActivity().getIntent();
		LatLon startPoint = null;
		if(intent != null){
			double lat = intent.getDoubleExtra(SEARCH_LAT, 0);
			double lon = intent.getDoubleExtra(SEARCH_LON, 0);
			if(lat != 0 || lon != 0){
				// Not commenting out the next line will cause the follwing issue:
				// (1) When the transport search was called from the dashboard, everything still works: You can change the search origin on the tab, this resets any previous (if any) search results, and any new searches will be centered around the new origin.
				// (2) But when the tab was called from map screen, changing the search origin on the tab keeps any search results, does not reset anything.
				// (3) If the search origin had been changed on anoother search tab before switching to this one, new transport searches would still center around the OLD search origin (the last map view)
				//startPoint = new LatLon(lat, lon);
			}
		}
		if(startPoint == null && getActivity() instanceof SearchActivity){
			startPoint = ((SearchActivity) getActivity()).getSearchPoint();
		}
		if (startPoint == null) {
			startPoint = settings.getLastKnownMapLocation();
		}
		OsmandApplication app = (OsmandApplication) getApplication();
		TargetPoint ps = app.getTargetPointsHelper().getPointToNavigate();
		LatLon pointToNavigate = ps == null ? null : ps.point;
		if(!Algorithms.objectEquals(pointToNavigate, this.destinationLocation) || 
				!Algorithms.objectEquals(startPoint, this.lastKnownMapLocation)){
			destinationLocation = pointToNavigate;
			selectedDestinationLocation = destinationLocation;
			lastKnownMapLocation = startPoint;
			searchTransport();			
		}
	}
	
	@Override
	public void locationUpdate(LatLon l) {
		if(!Algorithms.objectEquals(l, this.lastKnownMapLocation)){
			lastKnownMapLocation = l;
			if(view != null) { 
				searchTransport();
			}
		}
	}
	
	public String getSearchArea(){
		return " < " + 125 * (1 << (17 - zoom)) + " " + getString(R.string.m); //$NON-NLS-1$//$NON-NLS-2$
	}
	public boolean isSearchFurtherAvailable() {
		return zoom >= finalZoom;
	}
	
	public void searchTransport(){
		// use progress
		stopsAdapter.clear();
		searchArea.setText(getSearchArea());
		boolean routeCalculated = isRouteCalculated();
		searchTransportLevel.setEnabled(false);
		if (!routeCalculated && getLocationToStart() != null) {
			final LatLon locationToStart = getLocationToStart();
			final LatLon locationToGo = getLocationToGo();
			List<TransportIndexRepository> rs = ((OsmandApplication)getApplication()).getResourceManager().searchTransportRepositories(locationToStart.getLatitude(), 
					locationToStart.getLongitude());
			if(!rs.isEmpty()){
				final AsyncTask<?, ?, ?> previous = asyncTask;
				AsyncTask<TransportIndexRepository, Void, List<RouteInfoLocation>> current = 
						new AsyncTask<TransportIndexRepository, Void, List<RouteInfoLocation>>() {
					
					@Override
					protected void onPreExecute() {
						super.onPreExecute();
						progress.setVisibility(View.VISIBLE);
					}

					@Override
					protected List<RouteInfoLocation> doInBackground(TransportIndexRepository... params) {
						if(previous != null) {
							try {
								previous.get();
							} catch (Exception e) {
							}
						}
						List<RouteInfoLocation> res  = new ArrayList<TransportIndexRepository.RouteInfoLocation>();
						for(TransportIndexRepository repo : params){
							List<RouteInfoLocation> r = repo.searchTransportRouteStops(locationToStart.getLatitude(), locationToStart
									.getLongitude(), locationToGo, zoom);
							if(r != null) {
								res.addAll(r);
							}
						}
						return res;
					}
					@Override
					protected void onPostExecute(List<RouteInfoLocation> result) {
						// isAdded() here fixes the "not attached to Activity" FC when rapidly changing screen orientation
						if (isAdded()) {
							stopsAdapter.setNewModel(result);
							updateSearchMoreButton();
							searchArea.setText(getSearchArea());
							progress.setVisibility(View.INVISIBLE);
							asyncTask = null;
						}
					}
				};
				asyncTask = current;
				current.execute(rs.toArray(new TransportIndexRepository[rs.size()]));
			}
		} else {
			updateSearchMoreButton();
		}
	}
	
	private void updateSearchMoreButton() {
		if (!isRouteCalculated()) {
			searchTransportLevel.setEnabled(isSearchFurtherAvailable());
			searchTransportLevel.setText(R.string.search_POI_level_btn);
		} else {
			searchTransportLevel.setEnabled(true);
			searchTransportLevel.setText(R.string.transport_search_again);
		}
		
	}
	
	public String getInformation(RouteInfoLocation route, List<TransportStop> stops, int position, boolean part){
		StringBuilder text = new StringBuilder(200);
		double dist = 0;
		int ind = 0;
		int stInd = stops.size();
		int eInd = stops.size();
		for (TransportStop s : stops) {
			if (s == route.getStart()) {
				stInd = ind;
			} 
			if (s == route.getStop()) {
				eInd = ind;
			}
			if (ind > stInd && ind <= eInd) {
				dist += MapUtils.getDistance(stops.get(ind - 1).getLocation(), s.getLocation());
			}
			ind++;
		}
		text.append(getString(R.string.transport_route_distance)).append(" ").append(OsmAndFormatter.getFormattedDistance((int) dist, getApplication()));  //$NON-NLS-1$/
		if(!part){
			text.append(", ").append(getString(R.string.transport_stops_to_pass)).append(" ").append(eInd - stInd);   //$NON-NLS-1$ //$NON-NLS-2$
			LatLon endStop = getEndStop(position - 1);
			if (endStop != null) {
				String before = OsmAndFormatter.getFormattedDistance((int) MapUtils.getDistance(endStop, route.getStart().getLocation()), 
						getApplication());
				text.append(", ").append(getString(R.string.transport_to_go_before)).append(" ").append(before); //$NON-NLS-2$//$NON-NLS-1$
			}

			LatLon stStop = getStartStop(position + 1);
			if (stStop != null) {
				String after = OsmAndFormatter.getFormattedDistance((int) MapUtils.getDistance(stStop, route.getStop().getLocation()), getApplication());
				text.append(", ").append(getString(R.string.transport_to_go_after)).append(" ").append(after); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		
		return text.toString();
	}
	
	
	
	 @Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		final RouteInfoLocation item = stopsAdapter.getItem(position);
		Builder builder = new AlertDialog.Builder(getActivity());
		List<String> items = new ArrayList<String>();
		final List<TransportStop> stops = item.getDirection() ? item.getRoute().getForwardStops() : item.getRoute().getBackwardStops();
		LatLon locationToGo = getLocationToGo();
		LatLon locationToStart = getLocationToStart();
		builder.setTitle(getString(R.string.transport_stop_to_go_out)+"\n"+getInformation(item, stops, getCurrentRouteLocation(), true)); //$NON-NLS-1$
		int ind = 0;
		for(TransportStop st : stops){
			StringBuilder n = new StringBuilder(50);
			n.append(ind++);
			if(st == item.getStop()){
				n.append("!! "); //$NON-NLS-1$
			} else {
				n.append(". "); //$NON-NLS-1$
			}
			String name = st.getName(settings.usingEnglishNames());
			if(locationToGo != null){
				n.append(name).append(" - ["); //$NON-NLS-1$
				n.append(OsmAndFormatter.getFormattedDistance((int) MapUtils.getDistance(locationToGo, st.getLocation()),  getApplication())).append("]"); //$NON-NLS-1$ 
			} else if(locationToStart != null){
				n.append("[").append(OsmAndFormatter.getFormattedDistance((int) MapUtils.getDistance(locationToStart, st.getLocation()), getApplication())).append("] - "); //$NON-NLS-1$ //$NON-NLS-2$
				n.append(name);
			} else {
				n.append(name);
			}
			items.add(n.toString());
		}
		builder.setItems(items.toArray(new String[items.size()]), new DialogInterface.OnClickListener(){

			@Override
			public void onClick(DialogInterface dialog, int which) {
				if(which >= 0){
					TransportStop stop = stops.get(which);
					showContextMenuOnStop(stop, item, which);
				}
			}
			
		});
		builder.show();
	}
	
	
	public void showContextMenuOnStop(final TransportStop stop, final RouteInfoLocation route, final int stopInd) {
		Builder b = new AlertDialog.Builder(getActivity());
		b.setItems(new String[] { getString(R.string.transport_finish_search), getString(R.string.transport_search_before), getString(R.string.transport_search_after) },
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						int currentRouteCalculation = getCurrentRouteLocation();
						route.setStop(stop);
						route.setStopNumbers(stopInd);
						if (which == 0) {
							intermediateListAdapater.insert(route, currentRouteCalculation);
							intermediateListAdapater.remove(null);
							currentRouteCalculation = -1;
						} else if (which == 1) {
							intermediateListAdapater.insert(route, currentRouteCalculation + 1);
						} else if (which == 2) {
							intermediateListAdapater.insert(route, currentRouteCalculation);
						}
						// layout
						zoom = initialZoom;
						searchTransport();
					}

				});
		b.show();
	}
	
	public void showContextMenuOnRoute(final RouteInfoLocation route, final int routeInd) {
		Builder b = new AlertDialog.Builder(getActivity());
		List<TransportStop> stops = route.getDirection() ? route.getRoute().getForwardStops() : route.getRoute().getBackwardStops();
		boolean en = settings.usingEnglishNames();
		
		String info = getInformation(route, stops, routeInd, false);
		StringBuilder txt = new StringBuilder(300);
		txt.append(info);
		boolean start = false;
		for(TransportStop s : stops){
			if(s == route.getStart()){
				start = true;
			}
			if(start){
				txt.append("\n").append(getString(R.string.transport_Stop)).append(" : ").append(s.getName(en)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if(s == route.getStop()){
				break;
			}
		}
		
		b.setMessage(txt.toString());
		b.setPositiveButton(getString(R.string.shared_string_ok), null);
		b.setNeutralButton(getString(R.string.transport_search_before), new DialogInterface.OnClickListener(){
			@Override
			public void onClick(DialogInterface dialog, int which) {
				int toInsert = routeInd;
				int c = getCurrentRouteLocation();
				if(c >= 0 && c < routeInd){
					toInsert --;
				}
				intermediateListAdapater.remove(null);
				intermediateListAdapater.insert(null, toInsert);
				zoom = initialZoom;
				searchTransport();	
			}
		});
		b.setNegativeButton(getString(R.string.transport_search_after), new DialogInterface.OnClickListener(){
			@Override
			public void onClick(DialogInterface dialog, int which) {
				int toInsert = routeInd;
				int c = getCurrentRouteLocation();
				if(c > routeInd || c == -1){
					toInsert ++;
				}
				intermediateListAdapater.remove(null);
				intermediateListAdapater.insert(null, toInsert);
				zoom = initialZoom;
				searchTransport();	
			}
		});
		b.show();
	}
	
	public int getCurrentRouteLocation(){
		return intermediateListAdapater.getPosition(null);
	}

	public boolean isRouteCalculated(){
		return getCurrentRouteLocation() == -1;
	}
	
	public LatLon getLocationToStart() {
		return getEndStop(getCurrentRouteLocation() - 1);
	}
	
	public LatLon getLocationToGo() {
		return getStartStop(getCurrentRouteLocation() + 1);
	}
	
	public LatLon getStartStop(int position){
		if(position == intermediateListAdapater.getCount()){
			return selectedDestinationLocation;
		}
		RouteInfoLocation item = intermediateListAdapater.getItem(position);
		if(item == null){
			return getStartStop(position + 1);
		}
		return item.getStart().getLocation();
	}

	public LatLon getEndStop(int position){
		if(position < 0){
			return lastKnownMapLocation;
		}
		RouteInfoLocation item = intermediateListAdapater.getItem(position);
		if(item == null){
			return getEndStop(position -1);
		}
		return item.getStop().getLocation();
	}

	class TransportStopAdapter extends ArrayAdapter<RouteInfoLocation> {
		private List<RouteInfoLocation> model;

		TransportStopAdapter(List<RouteInfoLocation> list) {
			super(getActivity(), R.layout.search_transport_list_item, list);
			model = list;
		}

		public void setNewModel(List<RouteInfoLocation> stopsList) {
			this.model = stopsList;
			setNotifyOnChange(false);
			stopsAdapter.clear();
			for (RouteInfoLocation obj : stopsList) {
				this.add(obj);
			}
			setNotifyOnChange(true);
			this.notifyDataSetChanged();
			
		}
		
		public List<RouteInfoLocation> getModel() {
			return model;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View row = convertView;
			if (row == null) {
				LayoutInflater inflater = getActivity().getLayoutInflater();
				row = inflater.inflate(R.layout.search_transport_list_item, parent, false);
			}
			LatLon locationToGo = getLocationToGo();
			LatLon locationToStart = getLocationToStart();
			
			TextView label = (TextView) row.findViewById(R.id.label);
			ImageView icon = (ImageView) row.findViewById(R.id.search_icon);
			RouteInfoLocation stop = getItem(position);

			TransportRoute route = stop.getRoute();
			StringBuilder labelW = new StringBuilder(150);
			labelW.append(route.getType()).append(" ").append(route.getRef()); //$NON-NLS-1$
			labelW.append(" - ["); //$NON-NLS-1$
			
			if (locationToGo != null) {
				labelW.append(OsmAndFormatter.getFormattedDistance(stop.getDistToLocation(), getApplication()));
			} else {
				labelW.append(getString(R.string.transport_search_none));
			}
			labelW.append("]\n").append(route.getName(settings.usingEnglishNames())); //$NON-NLS-1$
			if (locationToGo != null && stop.getDistToLocation() < 400) {
				icon.setImageResource(R.drawable.opened_poi);
			} else {
				icon.setImageResource(R.drawable.poi);
			}
			
			int dist = locationToStart == null ? 0 : (int) (MapUtils.getDistance(stop.getStart().getLocation(), locationToStart));
			String distance =  OsmAndFormatter.getFormattedDistance(dist, getApplication()) + " "; //$NON-NLS-1$
			label.setText(distance + labelW, TextView.BufferType.SPANNABLE);
			((Spannable) label.getText()).setSpan(new ForegroundColorSpan(getResources().getColor(R.color.color_distance)), 0, distance.length() - 1, 0);
			return (row);
		}
	}
	
	
	class TransportRouteAdapter extends ArrayAdapter<RouteInfoLocation> {
		TransportRouteAdapter(List<RouteInfoLocation> list) {
			super(getActivity(), R.layout.search_transport_route_item, list);
		}

		@Override
		public View getView(final int position, View convertView, ViewGroup parent) {
			View row = convertView;
			final RouteInfoLocation info = getItem(position);
			if(info == null){
				TextView text = new TextView(getContext());
				LatLon st = getStartStop(position + 1);
				LatLon end = getEndStop(position - 1);

				if(st != null && end != null){
					int dist = (int) MapUtils.getDistance(st, end);
					text.setText(MessageFormat.format(getString(R.string.transport_searching_route), OsmAndFormatter.getFormattedDistance(dist, getApplication())));
				} else {
					text.setText(getString(R.string.transport_searching_transport));
				}
				text.setTextSize(21);
				text.setTypeface(null, Typeface.ITALIC);
				text.setOnClickListener(new View.OnClickListener(){

					@Override
					public void onClick(View v) {
						if(intermediateListAdapater.getCount() > 1){
							intermediateListAdapater.remove(null);
							searchTransport();
						} else {
							if(selectedDestinationLocation == null){
								selectedDestinationLocation = destinationLocation;
							} else {
								selectedDestinationLocation = null;
							}
							searchTransport();
						}
						
					}
					
				});
				return text;
			}
			int currentRouteLocation = getCurrentRouteLocation();
			if (row == null || row instanceof TextView) {
				LayoutInflater inflater = getActivity().getLayoutInflater();
				row = inflater.inflate(R.layout.search_transport_route_item, parent, false);
			}
			
			TextView label = (TextView) row.findViewById(R.id.label);
			ImageButton icon = (ImageButton) row.findViewById(R.id.remove);
			
			TransportRoute route = info.getRoute();
			icon.setVisibility(View.VISIBLE);
			StringBuilder labelW = new StringBuilder(150);
			labelW.append(route.getType()).append(" ").append(route.getRef()); //$NON-NLS-1$
			boolean en = settings.usingEnglishNames();
			labelW.append(" : ").append(info.getStart().getName(en)).append(" - ").append(info.getStop().getName(en)); //$NON-NLS-1$ //$NON-NLS-2$
			// additional information  if route is calculated
			if (currentRouteLocation == -1) {
				labelW.append(" ("); //$NON-NLS-1$
				labelW.append(info.getStopNumbers()).append(" ").append(getString(R.string.transport_stops)).append(", "); //$NON-NLS-1$ //$NON-NLS-2$
				int startDist = (int) MapUtils.getDistance(getEndStop(position - 1), info.getStart().getLocation());
				labelW.append(getString(R.string.transport_to_go_before)).append(" ").append(OsmAndFormatter.getFormattedDistance(startDist, getApplication())); //$NON-NLS-1$
				if (position == getCount() - 1) {
					LatLon stop = getStartStop(position + 1);
					if(stop != null) {
						int endDist = (int) MapUtils.getDistance(stop, info.getStop().getLocation());
						labelW.append(", ").append(getString(R.string.transport_to_go_after)).append(" ").append(OsmAndFormatter.getFormattedDistance(endDist, getApplication()));  //$NON-NLS-1$ //$NON-NLS-2$
					}
				}

				labelW.append(")"); //$NON-NLS-1$ 
				
			}
			label.setText(labelW.toString());
			icon.setOnClickListener(new View.OnClickListener(){
				@Override
				public void onClick(View v) {
					int p = position;
					intermediateListAdapater.remove(null);
					if(!isRouteCalculated() && getCurrentRouteLocation() < p){
						p--;
					}
					intermediateListAdapater.insert(null, p);
					intermediateListAdapater.remove(info);
					intermediateListAdapater.notifyDataSetChanged();
					zoom = initialZoom; 
					searchTransport();
					
				}
				
			});
			View.OnClickListener clickListener = new View.OnClickListener(){
				@Override
				public void onClick(View v) {
					showContextMenuOnRoute(info, position);
				}
				
			};
			label.setOnClickListener(clickListener);
			return row;
		}


	}

	@Override
	public void onCreateOptionsMenu(Menu onCreate, MenuInflater inflater) {
		if(getActivity() instanceof SearchActivity) {
			 ((SearchActivity) getActivity()).getClearToolbar(false);
		}
	}

}

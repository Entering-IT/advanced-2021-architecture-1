package ru.gaket.themoviedb.data.movies

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.gaket.themoviedb.core.MovieUrlProvider
import ru.gaket.themoviedb.data.movies.local.MovieEntity
import ru.gaket.themoviedb.data.movies.local.MoviesLocalDataSource
import ru.gaket.themoviedb.data.movies.remote.MoviesRemoteDataSource
import ru.gaket.themoviedb.data.review.local.MyReviewsLocalDataSource
import ru.gaket.themoviedb.data.review.local.toEntity
import ru.gaket.themoviedb.data.review.local.toModel
import ru.gaket.themoviedb.data.review.remote.ReviewsRemoteDataSource
import ru.gaket.themoviedb.domain.auth.User.*
import ru.gaket.themoviedb.domain.movies.models.Movie
import ru.gaket.themoviedb.domain.movies.models.MovieId
import ru.gaket.themoviedb.domain.movies.models.MovieWithReviews
import ru.gaket.themoviedb.domain.movies.models.SearchMovie
import ru.gaket.themoviedb.domain.movies.models.SearchMovieWithMyReview
import ru.gaket.themoviedb.domain.movies.toModel
import ru.gaket.themoviedb.domain.movies.toSearchMovie
import ru.gaket.themoviedb.domain.review.models.ReviewDraft
import ru.gaket.themoviedb.domain.review.models.MyReview
import ru.gaket.themoviedb.domain.review.models.SomeoneReview
import ru.gaket.themoviedb.util.Result
import ru.gaket.themoviedb.util.VoidResult
import ru.gaket.themoviedb.util.doOnSuccess
import ru.gaket.themoviedb.util.mapNestedSuccess
import ru.gaket.themoviedb.util.mapSuccess
import ru.gaket.themoviedb.util.toSuccessOrErrorList
import timber.log.Timber
import javax.inject.Inject

interface MoviesRepository {

    /**
     * Search [SearchMovieEntity]s for the given [query] string
     *
     * @return List of [SearchMovieEntity], null as error, or empty list.
     * No Throwable.
     */
    suspend fun searchMoviesWithReviews(
        query: String,
        page: Int = 1,
    ): Result<List<SearchMovieWithMyReview>, Throwable>

    /**
     * Get [MovieEntity] by movieId
     *
     * @return List of [SearchMovieEntity], null as error, or empty list.
     * No Throwable.
     */
    suspend fun getMovieDetails(id: MovieId): Result<Movie, Throwable>

    suspend fun getMovieDetailsWithReviews(id: MovieId): Result<MovieWithReviews, Throwable>

    suspend fun addReview(
        draft: ReviewDraft,
        authorId: Id,
        authorEmail: Email,
    ): VoidResult<Throwable>

    suspend fun getReviewsForUser(userId: Id)

    suspend fun deleteUserReviews()
}

private suspend fun MoviesRepository.getMovieDetailsList(ids: Set<MovieId>): Result<List<Movie>, List<Throwable>> =
    coroutineScope {
        val asyncCalls = ids.map { singleId ->
            async { getMovieDetails(singleId) }
        }

        asyncCalls.awaitAll()
            .toSuccessOrErrorList()
    }

/**
 * Repository providing data about [MovieEntity], [SearchMovieEntity]
 */
class MoviesRepositoryImpl @Inject constructor(
    private val moviesRemoteDataSource: MoviesRemoteDataSource,
    private val moviesLocalDataSource: MoviesLocalDataSource,
    private val reviewsRemoteDataSource: ReviewsRemoteDataSource,
    private val myReviewsLocalDataSource: MyReviewsLocalDataSource,
    private val movieUrlProvider: MovieUrlProvider,
) : MoviesRepository {

    override suspend fun searchMoviesWithReviews(
        query: String,
        page: Int,
    ): Result<List<SearchMovieWithMyReview>, Throwable> =
        searchMovies(query, page)
            .mapSuccess { movies -> fillSearchMoviesWithMyReviews(movies) }

    private suspend fun searchMovies(query: String, page: Int): Result<List<SearchMovie>, Throwable> =
        moviesRemoteDataSource.searchMovies(query, page)
            .mapSuccess { dtos -> dtos.map { singleDto -> singleDto.toEntity(movieUrlProvider.baseImageUrl) } }
            .doOnSuccess { movieEntities -> moviesLocalDataSource.insertAll(movieEntities) }
            .mapSuccess { entries -> entries.map { movieEntity -> movieEntity.toSearchMovie() } }

    private suspend fun fillSearchMoviesWithMyReviews(movies: List<SearchMovie>): List<SearchMovieWithMyReview> {
        val movieIds = movies.map { singleMovie -> singleMovie.id }
        val myReviews = getMyReviewsForMovies(movieIds)

        return movies.map { singleMovie ->
            SearchMovieWithMyReview(
                movie = singleMovie,
                myReview = myReviews[singleMovie.id]
            )
        }
    }

    private suspend fun getMyReviewsForMovies(movieIds: List<MovieId>): Map<MovieId, MyReview> =
        myReviewsLocalDataSource.getByMovieIds(movieIds)
            .map { myReviewEntity -> myReviewEntity.toModel() }
            .associateBy { myReview -> myReview.movieId }

    override suspend fun getMovieDetails(id: MovieId): Result<Movie, Throwable> {
        val cachedMovie = moviesLocalDataSource.getById(id)
        val entityResult = if (cachedMovie != null && cachedMovie.duration > 0) {
            Result.Success(cachedMovie)
        } else {
            loadMovieDetailsFromRemoteAndStore(id)
        }

        return entityResult.mapSuccess { entity -> entity.toModel() }
    }

    private suspend fun loadMovieDetailsFromRemoteAndStore(id: MovieId): Result<MovieEntity, Throwable> =
        moviesRemoteDataSource.getMovieDetails(id)
            .mapSuccess { dto -> dto.toEntity(movieUrlProvider.baseImageUrl) }
            .doOnSuccess { entity -> moviesLocalDataSource.insert(entity) }

    override suspend fun getMovieDetailsWithReviews(id: MovieId): Result<MovieWithReviews, Throwable> {
        val (details, allReviewsResult, myReview) = getMovieDetailsWithReviewsTriple(id)

        return details.mapNestedSuccess { movie ->
            allReviewsResult.mapSuccess { allReviews ->
                MovieWithReviews(
                    movie = movie,
                    someoneElseReviews = allReviews.filter { someoneReview -> someoneReview.review.id != myReview?.review?.id },
                    myReview = myReview
                )
            }
        }
    }

    private suspend fun getMovieDetailsWithReviewsTriple(
        id: MovieId,
    ): Triple<Result<Movie, Throwable>, Result<List<SomeoneReview>, Throwable>, MyReview?> =
        coroutineScope {
            val allReviewsResultCall = async { reviewsRemoteDataSource.getMovieReviews(id) }
            val detailsCall = async { getMovieDetails(id) }
            val myReviewCall = async { myReviewsLocalDataSource.getByMovieId(id)?.toModel() }

            Triple(
                detailsCall.await(),
                allReviewsResultCall.await(),
                myReviewCall.await()
            )
        }

    override suspend fun addReview(
        draft: ReviewDraft,
        authorId: Id,
        authorEmail: Email,
    ): VoidResult<Throwable> =
        reviewsRemoteDataSource.addReview(draft, authorId, authorEmail)
            .mapSuccess { newReview ->
                val entity = newReview.toEntity()
                myReviewsLocalDataSource.insert(entity)
            }

    override suspend fun getReviewsForUser(userId: Id) {
        when (val myReviewsResult = getAndStoreMyReviews(userId)) {
            is Result.Success -> {
                getAndStoreMyReviewedMovies(myReviewsResult.result)
            }
            is Result.Error -> {
                Timber.e("sync Local storage error", myReviewsResult.result)
            }
        }
    }

    override suspend fun deleteUserReviews() = myReviewsLocalDataSource.deleteAll()

    private suspend fun getAndStoreMyReviews(userId: Id): Result<List<MyReview>, Throwable> =
        reviewsRemoteDataSource.getMyReviews(userId)
            .doOnSuccess { myReviews ->
                val entities = myReviews.map { model -> model.toEntity() }
                myReviewsLocalDataSource.insertAll(entities)
            }

    private suspend fun getAndStoreMyReviewedMovies(result: List<MyReview>) {
        val myReviewedMovieIds = result
            .map { review -> review.movieId }
            .toSet()

        getMovieDetailsList(ids = myReviewedMovieIds)
    }
}
